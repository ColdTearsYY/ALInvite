package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邀请码生成与邀请绑定。
 * 生成流程全链无 join：候选码生成与查重走 IO 线程池，同线程直查。
 */
public class InviteManager {

    private final ALInvite plugin;

    public InviteManager(ALInvite plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家当前可用的第一个邀请码。**不会现场生成**：没有库存时返回 null，
     * 库存只由配额水位事务（累计在线时长）或旧数据迁移产生。
     */
    public CompletableFuture<String> ensureInviteCode(UUID uuid) {
        String cached = plugin.getCacheManager().getInviteCode(uuid);
        if (cached != null && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }
        return AsyncPool.supply(() -> {
            String code = plugin.getDatabaseManager().getAvailableCodeSync(uuid);
            if (code != null && !code.isEmpty()) {
                plugin.getCacheManager().setInviteCode(uuid, code);
            }
            return code;
        });
    }

    /** 全部可用邀请码（/alinvite code 展示用）。 */
    public CompletableFuture<java.util.List<String>> listAvailableCodes(UUID uuid) {
        return plugin.getDatabaseManager().listAvailableCodes(uuid);
    }

    /**
     * 兼容旧调用：返回第一个可用码。没有可发放库存时以异常结束，绝不凭空造码。
     */
    public CompletableFuture<String> generateInviteCode(UUID uuid) {
        return ensureInviteCode(uuid).thenCompose(code -> code != null && !code.isEmpty()
            ? CompletableFuture.completedFuture(code)
            : CompletableFuture.failedFuture(new IllegalStateException(
                "没有可发放的邀请码库存（需累计在线时长达到发放节点）")));
    }

    public CompletableFuture<Boolean> isCodeExists(String code) {
        return plugin.getDatabaseManager().isInviteCodeExists(code);
    }

    public CompletableFuture<UUID> getInviterByCode(String code) {
        return plugin.getDatabaseManager().getInviterUUIDByInviteCode(code);
    }

    /** 兼容旧 API：实际写入统一走原子邀请事务。 */
    public CompletableFuture<InviteResult> processInvite(Player invitee, String code) {
        return bindInviteCode(invitee, code).thenApply(result -> {
            if (result.success) {
                return new InviteResult(true, InviteResultType.SUCCESS, null);
            }
            return switch (result.type) {
                case CODE_NOT_FOUND -> new InviteResult(false, InviteResultType.INVALID_CODE);
                case ALREADY_USED -> new InviteResult(false, InviteResultType.ALREADY_USED);
                case IP_LIMIT -> new InviteResult(false, InviteResultType.IP_LIMIT);
                case SELF_INVITE -> new InviteResult(false, InviteResultType.SELF_INVITE);
                case INVITER_LIMIT_REACHED -> new InviteResult(false, InviteResultType.INVITER_LIMIT_REACHED);
                case QUOTA_EXHAUSTED -> new InviteResult(false, InviteResultType.QUOTA_EXHAUSTED);
                default -> new InviteResult(false, InviteResultType.UNKNOWN);
            };
        });
    }

    public CompletableFuture<Integer> getTotalInvites(UUID uuid) {
        return plugin.getCacheManager().getStatsAsync(uuid);
    }

    public CompletableFuture<String> getInviteCode(UUID uuid) {
        return plugin.getCacheManager().getInviteCodeAsync(uuid);
    }

    private CompletableFuture<String> getPlayerIpAsync(UUID uuid) {
        CompletableFuture<String> future = new CompletableFuture<>();
        plugin.getScheduler().runGlobal(() -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                future.complete(null);
                return;
            }
            plugin.getScheduler().supplyAtPlayer(online, () ->
                online.getAddress() == null || online.getAddress().getAddress() == null
                    ? null : online.getAddress().getAddress().getHostAddress())
                .whenComplete((ip, error) -> future.complete(error == null ? ip : null));
        });
        return future;
    }

    private record BindSnapshot(UUID uuid, String name, String ip,
                                boolean usePermission, boolean veteran) {}

    public CompletableFuture<BindResult> bindInviteCode(Player player, String code) {
        String trimmedCode = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (trimmedCode.isEmpty()) {
            return CompletableFuture.completedFuture(new BindResult(false, BindResultType.CODE_NOT_FOUND));
        }

        String usePerm = plugin.getConfigManager().getConfig()
            .getString("invite_code.use_permission", "alinvite.use");
        String veteranPerm = plugin.getConfigManager().getConfig()
            .getString("invite_code.veteran_permission", "alinvite.veteran");

        return plugin.getScheduler().supplyAtPlayer(player, () -> {
            if (player.getAddress() == null || player.getAddress().getAddress() == null) {
                return null;
            }
            return new BindSnapshot(
                player.getUniqueId(),
                player.getName(),
                player.getAddress().getAddress().getHostAddress(),
                player.hasPermission(usePerm),
                player.hasPermission(veteranPerm));
        }).thenCompose(snapshot -> {
            if (snapshot == null) {
                return CompletableFuture.completedFuture(new BindResult(false, BindResultType.UNKNOWN));
            }
            if (!snapshot.usePermission()) {
                return CompletableFuture.completedFuture(new BindResult(false, BindResultType.NO_PERMISSION));
            }
            boolean allowVeteranToBind = plugin.getConfigManager().getConfig()
                .getBoolean("invite_code.allow_veteran_to_bind", false);
            return plugin.getDatabaseManager().hasRegistrationClaim(snapshot.uuid())
                .thenCompose(openRegistered -> {
                    if (!allowVeteranToBind && snapshot.veteran() && !openRegistered) {
                        return CompletableFuture.completedFuture(
                            new BindResult(false, BindResultType.VETERAN_CANNOT_BIND));
                    }

                    return isCodeExists(trimmedCode).thenCompose(codeExists -> {
                        if (!codeExists) {
                            return CompletableFuture.completedFuture(
                                new BindResult(false, BindResultType.CODE_NOT_FOUND));
                        }
                        return plugin.getDatabaseManager().hasUsedInviteCode(snapshot.uuid())
                            .thenCompose(hasUsed -> {
                                if (hasUsed) {
                                    return CompletableFuture.completedFuture(
                                        new BindResult(false, BindResultType.ALREADY_USED));
                                }
                                return getInviterByCode(trimmedCode).thenCompose(inviterUuid -> {
                                    if (inviterUuid == null) {
                                        return CompletableFuture.completedFuture(
                                            new BindResult(false, BindResultType.CODE_NOT_FOUND));
                                    }
                                    if (inviterUuid.equals(snapshot.uuid())) {
                                        return CompletableFuture.completedFuture(
                                            new BindResult(false, BindResultType.SELF_INVITE));
                                    }
                                    return getPlayerIpAsync(inviterUuid).thenCompose(inviterIp -> {
                                        if (plugin.getConfigManager().getConfig()
                                                .getBoolean("ip_restriction.enabled", true)
                                                && plugin.getConfigManager().getConfig()
                                                    .getBoolean("ip_restriction.prevent_self_ip", true)
                                                && inviterIp != null && inviterIp.equals(snapshot.ip())) {
                                            return CompletableFuture.completedFuture(
                                                new BindResult(false, BindResultType.SELF_INVITE));
                                        }
                                        return doBindInviteRecord(snapshot.uuid(), snapshot.ip(), snapshot.name(),
                                            inviterUuid, inviterIp, trimmedCode, player);
                                    });
                                });
                            });
                    });
                });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("绑定邀请码时发生错误: " + ex.getMessage());
            return new BindResult(false, BindResultType.UNKNOWN);
        });
    }

    public enum BindResultType {
        SUCCESS,
        NO_PERMISSION,
        CODE_NOT_FOUND,
        ALREADY_USED,
        IP_LIMIT,
        SELF_INVITE,
        VETERAN_CANNOT_BIND,
        INVITER_LIMIT_REACHED,
        QUOTA_EXHAUSTED,
        UNKNOWN
    }

    public static class BindResult {
        public final boolean success;
        public final BindResultType type;
        public final int remainingSlots;
        public final long nextSlotAt;

        public BindResult(boolean success, BindResultType type) {
            this(success, type, -1, 0L);
        }

        public BindResult(boolean success, BindResultType type, int remainingSlots, long nextSlotAt) {
            this.success = success;
            this.type = type;
            this.remainingSlots = remainingSlots;
            this.nextSlotAt = nextSlotAt;
        }
    }

    public enum InviteResultType {
        SUCCESS,
        INVALID_CODE,
        ALREADY_USED,
        IP_LIMIT,
        SELF_INVITE,
        INVITER_LIMIT_REACHED,
        QUOTA_EXHAUSTED,
        UNKNOWN
    }

    public static class InviteResult {
        public final boolean success;
        public final InviteResultType type;
        public final UUID inviterUuid;

        public InviteResult(boolean success, InviteResultType type) {
            this(success, type, null);
        }

        public InviteResult(boolean success, InviteResultType type, UUID inviterUuid) {
            this.success = success;
            this.type = type;
            this.inviterUuid = inviterUuid;
        }
    }

    private CompletableFuture<BindResult> doBindInviteRecord(UUID playerUuid, String playerIp, String playerName,
                                                               UUID inviterUuid, String inviterIp,
                                                               String trimmedCode, Player player) {
        CompletableFuture<Void> settled = plugin.getInviteQuotaManager() == null
            ? CompletableFuture.completedFuture(null)
            : plugin.getInviteQuotaManager().refreshOnline(inviterUuid);
        return settled.thenCompose(ignored -> plugin.getDatabaseManager().recordInviteWithQuota(
                inviterUuid, playerUuid, playerIp, inviterIp, playerName, trimmedCode))
            .thenApply(dbResult -> {
                switch (dbResult.status) {
                    case SUCCESS -> {
                        completeSuccessfulInvite(player, inviterUuid, trimmedCode, dbResult);
                        return new BindResult(true, BindResultType.SUCCESS,
                            dbResult.remainingSlots, dbResult.nextSlotAt);
                    }
                    case DUPLICATE -> {
                        return new BindResult(false, BindResultType.ALREADY_USED);
                    }
                    case QUOTA_EXHAUSTED -> {
                        return new BindResult(false, BindResultType.QUOTA_EXHAUSTED,
                            dbResult.remainingSlots, dbResult.nextSlotAt);
                    }
                    case INVITER_LIMIT_REACHED -> {
                        return new BindResult(false, BindResultType.INVITER_LIMIT_REACHED,
                            dbResult.remainingSlots, dbResult.nextSlotAt);
                    }
                    case INVITEE_VETERAN -> {
                        return new BindResult(false, BindResultType.VETERAN_CANNOT_BIND,
                            dbResult.remainingSlots, dbResult.nextSlotAt);
                    }
                    case IP_LIMIT -> {
                        return new BindResult(false, BindResultType.IP_LIMIT,
                            dbResult.remainingSlots, dbResult.nextSlotAt);
                    }
                    case CODE_NOT_FOUND, CODE_NOT_AVAILABLE -> {
                        return new BindResult(false, BindResultType.CODE_NOT_FOUND,
                            dbResult.remainingSlots, dbResult.nextSlotAt);
                    }
                    default -> {
                        return new BindResult(false, BindResultType.UNKNOWN);
                    }
                }
            });
    }

    private record PendingClaimSnapshot(UUID uuid, String name, String ip) {}

    public CompletableFuture<BindResult> claimPendingInvite(Player player) {
        return claimPendingInvite(player, () -> true);
    }

    public CompletableFuture<BindResult> claimPendingInvite(Player player,
            java.util.function.BooleanSupplier admissionPermit) {
        return plugin.getScheduler().supplyAtPlayer(player, () -> {
            if (!player.isOnline() || player.getAddress() == null || player.getAddress().getAddress() == null) {
                return null;
            }
            return new PendingClaimSnapshot(player.getUniqueId(), player.getName(),
                player.getAddress().getAddress().getHostAddress());
        }).thenCompose(snapshot -> {
            if (snapshot == null) {
                return CompletableFuture.completedFuture(new BindResult(false, BindResultType.UNKNOWN));
            }
            String normalizedName = snapshot.name().toLowerCase(java.util.Locale.ROOT);
            return plugin.getDatabaseManager().getPendingInvite(normalizedName).thenCompose(pending -> {
                if (pending == null) {
                    return CompletableFuture.completedFuture(null);
                }
                CompletableFuture<Void> settled = plugin.getInviteQuotaManager() == null
                    ? CompletableFuture.completedFuture(null)
                    : plugin.getInviteQuotaManager().refreshOnline(pending.inviterUuid());
                return settled.thenCompose(ignored -> plugin.getDatabaseManager().claimPendingInvite(
                    pending, snapshot.uuid(), snapshot.ip(), snapshot.name(), normalizedName, admissionPermit))
                    .thenApply(dbResult -> {
                        if (dbResult.status == com.alinvite.database.DatabaseManager.InviteRecordStatus.SUCCESS) {
                            completeSuccessfulInvite(player, pending.inviterUuid(), pending.inviterCode(), dbResult);
                            Player inviter = Bukkit.getPlayer(pending.inviterUuid());
                            if (inviter != null) plugin.getScheduler().runAtPlayer(inviter, () -> inviter.sendMessage(
                                plugin.getConfigManager().getMessage("commands.add.claimed", inviter)
                                    .replace("{player}", snapshot.name())));
                            return new BindResult(true, BindResultType.SUCCESS,
                                dbResult.remainingSlots, dbResult.nextSlotAt);
                        }
                        if (dbResult.status == com.alinvite.database.DatabaseManager.InviteRecordStatus.ADMISSION_CLOSED) {
                            return null; // The reservation remains valid for a later login within its stored TTL.
                        }
                        // 只有确定无法认领（已绑定 / IP 限制 / 已是老玩家）才释放预留；
                        // 数据库临时错误保留预留，稍后重试，避免误退库存。
                        boolean transientFailure = dbResult.status
                            == com.alinvite.database.DatabaseManager.InviteRecordStatus.ERROR;
                        if (!transientFailure) {
                            plugin.getDatabaseManager().releasePendingInvite(pending);
                        }
                        return switch (dbResult.status) {
                            case DUPLICATE -> new BindResult(false, BindResultType.ALREADY_USED);
                            case IP_LIMIT -> new BindResult(false, BindResultType.IP_LIMIT);
                            case INVITEE_VETERAN -> new BindResult(false, BindResultType.VETERAN_CANNOT_BIND);
                            default -> new BindResult(false, BindResultType.UNKNOWN);
                        };
                    });
            });
        });
    }

    private void completeSuccessfulInvite(Player player, UUID inviterUuid, String inviteCode,
                                          com.alinvite.database.DatabaseManager.InviteRecordResult dbResult) {
        plugin.getCacheManager().invalidateStats(inviterUuid);
        plugin.getCacheManager().invalidateInviteCode(player.getUniqueId());
        plugin.getMilestoneManager().checkMilestones(inviterUuid, dbResult.totalInvites);
        if (plugin.getBindingGateManager() != null) {
            plugin.getBindingGateManager().unlock(player);
        }

        plugin.getScheduler().runGlobal(() -> {
            com.alinvite.api.ALInviteAPI.fireInviteSuccess(inviterUuid, player.getUniqueId());
            Bukkit.getPluginManager().callEvent(
                new com.alinvite.api.event.InviteBindEvent(player, inviterUuid, inviteCode));
        });
        plugin.getDeliveryManager().pump(player.getUniqueId());
        plugin.getDeliveryManager().pump(inviterUuid);
        plugin.getScheduler().runAtPlayer(player, () -> {
            String path = dbResult.milestoneEnabled && dbResult.rebateEnabled && dbResult.giftEnabled
                ? "function_restrictions.bind_success_full"
                : "function_restrictions.bind_success_limited";
            player.sendMessage(plugin.getConfigManager().getMessage(path, player));
            if (!dbResult.milestoneEnabled) {
                player.sendMessage(plugin.getConfigManager().getMessage(
                    "function_restrictions.bind_success_milestone_disabled", player));
            }
            if (!dbResult.rebateEnabled) {
                player.sendMessage(plugin.getConfigManager().getMessage(
                    "function_restrictions.bind_success_rebate_disabled", player));
            }
            if (!dbResult.giftEnabled) {
                player.sendMessage(plugin.getConfigManager().getMessage(
                    "function_restrictions.bind_success_gift_disabled", player));
            }
        });
    }


}
