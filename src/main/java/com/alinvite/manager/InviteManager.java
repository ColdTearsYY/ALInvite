package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邀请码生成与邀请绑定。
 * 生成流程全链无 join：候选码生成与查重走 IO 线程池，同线程直查。
 */
public class InviteManager {

    private final ALInvite plugin;
    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> generatingCodes = ConcurrentHashMap.newKeySet();

    public InviteManager(ALInvite plugin) {
        this.plugin = plugin;
    }

    /** 确保玩家已有邀请码（无则生成），返回当前邀请码。 */
    public CompletableFuture<String> ensureInviteCode(UUID uuid) {
        String cached = plugin.getCacheManager().getInviteCode(uuid);
        if (cached != null && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }
        return AsyncPool.supply(() -> plugin.getDatabaseManager().getInviteCodeByPlayerSync(uuid))
            .thenCompose(code -> code != null && !code.isEmpty()
                ? CompletableFuture.completedFuture(code)
                : generateInviteCode(uuid));
    }

    public CompletableFuture<String> generateInviteCode(UUID uuid) {
        if (!generatingCodes.add(uuid)) {
            String cached = plugin.getCacheManager().getInviteCode(uuid);
            return CompletableFuture.completedFuture(cached);
        }

        return uniqueCode(0)
            .thenCompose(code -> plugin.getDatabaseManager().getPlayerData(uuid).thenCompose(data ->
                data != null
                    ? plugin.getDatabaseManager().updateInviteCode(uuid, code).thenApply(v -> code)
                    : plugin.getDatabaseManager().createPlayerData(uuid, code).thenApply(v -> code)))
            .thenApply(code -> {
                plugin.getCacheManager().setInviteCode(uuid, code);
                return code;
            })
            .whenComplete((code, throwable) -> generatingCodes.remove(uuid));
    }

    private CompletableFuture<String> uniqueCode(int attempt) {
        return AsyncPool.supply(() -> {
            String code = randomCode();
            return plugin.getDatabaseManager().getPlayerByInviteCodeSync(code) != null ? null : code;
        }).thenCompose(code -> {
            if (code != null) {
                return CompletableFuture.completedFuture(code);
            }
            if (attempt >= 10) {
                plugin.getLogger().warning("生成唯一邀请码已尝试超过 10 次，使用最后一次候选码");
                return AsyncPool.supply(this::randomCode);
            }
            return uniqueCode(attempt + 1);
        });
    }

    private String randomCode() {
        int length = plugin.getConfigManager().getConfig().getInt("invite_code.length", 6);
        String charset = plugin.getConfigManager().getConfig().getString("invite_code.charset", "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789");
        String prefix = plugin.getConfigManager().getConfig().getString("invite_code.prefix", "");

        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(charset.charAt(random.nextInt(charset.length())));
        }
        return sb.toString();
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
            if (!allowVeteranToBind && snapshot.veteran()) {
                return CompletableFuture.completedFuture(new BindResult(false, BindResultType.VETERAN_CANNOT_BIND));
            }

            return isCodeExists(trimmedCode).thenCompose(codeExists -> {
                if (!codeExists) {
                    return CompletableFuture.completedFuture(new BindResult(false, BindResultType.CODE_NOT_FOUND));
                }
                return plugin.getDatabaseManager().hasUsedInviteCode(snapshot.uuid()).thenCompose(hasUsed -> {
                    if (hasUsed) {
                        return CompletableFuture.completedFuture(new BindResult(false, BindResultType.ALREADY_USED));
                    }
                    return getInviterByCode(trimmedCode).thenCompose(inviterUuid -> {
                        if (inviterUuid == null) {
                            return CompletableFuture.completedFuture(new BindResult(false, BindResultType.CODE_NOT_FOUND));
                        }
                        if (inviterUuid.equals(snapshot.uuid())) {
                            return CompletableFuture.completedFuture(new BindResult(false, BindResultType.SELF_INVITE));
                        }
                        return getPlayerIpAsync(inviterUuid).thenCompose(inviterIp -> {
                            if (plugin.getConfigManager().getConfig()
                                    .getBoolean("ip_restriction.enabled", true)
                                    && plugin.getConfigManager().getConfig()
                                        .getBoolean("ip_restriction.prevent_self_ip", true)
                                    && inviterIp != null && inviterIp.equals(snapshot.ip())) {
                                return CompletableFuture.completedFuture(new BindResult(false, BindResultType.SELF_INVITE));
                            }
                            return doBindInviteRecord(snapshot.uuid(), snapshot.ip(), snapshot.name(),
                                inviterUuid, inviterIp, trimmedCode, player);
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
        return plugin.getDatabaseManager().recordInviteWithQuota(
                inviterUuid, playerUuid, playerIp, inviterIp, playerName, trimmedCode)
            .thenApply(dbResult -> {
                switch (dbResult.status) {
                    case SUCCESS -> {
                        plugin.getCacheManager().invalidateStats(inviterUuid);
                        plugin.getCacheManager().invalidateInviteCode(playerUuid);
                        plugin.getMilestoneManager().checkMilestones(inviterUuid, dbResult.totalInvites);

                        plugin.getScheduler().runGlobal(() -> {
                            com.alinvite.api.ALInviteAPI.fireInviteSuccess(inviterUuid, playerUuid);
                            Bukkit.getPluginManager().callEvent(
                                new com.alinvite.api.event.InviteBindEvent(player, inviterUuid, trimmedCode));
                            registerWhitelist(playerName);
                        });
                        plugin.getGiftManager().giveGiftRewards(player, inviterUuid);
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
                    default -> {
                        return new BindResult(false, BindResultType.UNKNOWN);
                    }
                }
            });
    }

    private void registerWhitelist(String playerName) {
        if (!plugin.getConfigManager().getConfig().getBoolean("whitelist_on_bind.enabled", true)) {
            return;
        }
        String command = plugin.getConfigManager().getConfig()
            .getString("whitelist_on_bind.command", "whitelist add {player}")
            .replace("{player}", playerName);
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception e) {
            plugin.getLogger().warning("绑定后白名单登记失败: " + e.getMessage());
        }
    }

}
