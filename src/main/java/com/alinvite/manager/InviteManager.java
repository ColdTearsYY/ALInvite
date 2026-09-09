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

    public CompletableFuture<InviteResult> processInvite(Player invitee, String code) {
        String inviteeIp;
        UUID inviteeUuid;
        String inviteeName;
        try {
            inviteeIp = invitee.getAddress().getAddress().getHostAddress();
            inviteeUuid = invitee.getUniqueId();
            inviteeName = invitee.getName();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new InviteResult(false, InviteResultType.UNKNOWN));
        }

        return plugin.getDatabaseManager().getIpInviteCount(inviteeIp)
            .thenCompose(currentCount -> {
                if (plugin.getConfigManager().getConfig().getBoolean("ip_restriction.enabled", true)) {
                    int maxInvites = plugin.getConfigManager().getConfig().getInt("ip_restriction.max_invites_per_ip", 1);
                    if (maxInvites > 0 && currentCount >= maxInvites) {
                        return CompletableFuture.completedFuture(new InviteResult(false, InviteResultType.IP_LIMIT));
                    }
                }
                return CompletableFuture.completedFuture(null);
            })
            .thenCompose(result -> {
                if (result != null) return CompletableFuture.completedFuture(result);

                return plugin.getDatabaseManager().hasUsedInviteCode(inviteeUuid).thenCompose(hasUsed -> {
                    if (hasUsed) {
                        return CompletableFuture.completedFuture(new InviteResult(false, InviteResultType.ALREADY_USED));
                    }
                    return CompletableFuture.completedFuture(null);
                });
            })
            .thenCompose(result -> {
                if (result != null) return CompletableFuture.completedFuture(result);

                return getInviterByCode(code).thenCompose(inviterUuid -> {
                    if (inviterUuid == null) {
                        return CompletableFuture.completedFuture(new InviteResult(false, InviteResultType.INVALID_CODE));
                    }

                    if (inviterUuid.equals(inviteeUuid)) {
                        return CompletableFuture.completedFuture(new InviteResult(false, InviteResultType.SELF_INVITE));
                    }

                    if (plugin.getConfigManager().getConfig().getBoolean("ip_restriction.prevent_self_ip", true)) {
                        String inviterIp = getPlayerIp(inviterUuid);
                        if (inviterIp != null && inviterIp.equals(inviteeIp)) {
                            return CompletableFuture.completedFuture(new InviteResult(false, InviteResultType.SELF_INVITE));
                        }
                    }

                    int maxInvites = plugin.getConfigManager().getConfig().getInt("invite_code.max_invites", 0);
                    if (maxInvites > 0) {
                        return plugin.getDatabaseManager().getPlayerData(inviterUuid).thenCompose(inviterData -> {
                            if (inviterData != null && inviterData.totalInvites >= maxInvites) {
                                return CompletableFuture.completedFuture(new InviteResult(false, InviteResultType.INVITER_LIMIT_REACHED));
                            }
                            return doAddInviteRecord(inviterUuid, inviteeUuid, inviteeIp, inviteeName);
                        });
                    }

                    return doAddInviteRecord(inviterUuid, inviteeUuid, inviteeIp, inviteeName);
                });
            })
            .exceptionally(ex -> {
                plugin.getLogger().severe("处理邀请时发生错误: " + ex.getMessage());
                return new InviteResult(false, InviteResultType.UNKNOWN);
            });
    }

    private String getPlayerIp(UUID uuid) {
        return Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getUniqueId().equals(uuid))
            .findFirst()
            .map(p -> p.getAddress().getAddress().getHostAddress())
            .orElse(null);
    }

    private CompletableFuture<InviteResult> doAddInviteRecord(UUID inviterUuid, UUID inviteeUuid, String inviteeIp, String inviteeName) {
        return plugin.getDatabaseManager().addInviteRecord(inviterUuid, inviteeUuid, inviteeIp, inviteeName)
            .thenCompose(v -> plugin.getDatabaseManager().getPlayerData(inviterUuid))
            .thenCompose(data -> {
                if (data != null) {
                    int newTotal = data.totalInvites + 1;
                    return plugin.getDatabaseManager().updateInviteCount(inviterUuid, newTotal)
                        .thenAccept(v -> {
                            plugin.getCacheManager().invalidateStats(inviterUuid);
                            plugin.getMilestoneManager().checkMilestones(inviterUuid, newTotal);
                        })
                        .thenApply(v -> new InviteResult(true, InviteResultType.SUCCESS, inviterUuid));
                }
                return CompletableFuture.completedFuture(new InviteResult(true, InviteResultType.SUCCESS, inviterUuid));
            });
    }

    public CompletableFuture<Integer> getTotalInvites(UUID uuid) {
        return plugin.getCacheManager().getStatsAsync(uuid);
    }

    public CompletableFuture<String> getInviteCode(UUID uuid) {
        return plugin.getCacheManager().getInviteCodeAsync(uuid);
    }

    public CompletableFuture<BindResult> bindInviteCode(Player player, String code) {
        String playerIp;
        UUID playerUuid;
        String playerName;
        try {
            playerIp = player.getAddress().getAddress().getHostAddress();
            playerUuid = player.getUniqueId();
            playerName = player.getName();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new BindResult(false, BindResultType.UNKNOWN));
        }

        String usePerm = plugin.getConfigManager().getConfig()
            .getString("invite_code.use_permission", "alinvite.use");
        if (!player.hasPermission(usePerm)) {
            return CompletableFuture.completedFuture(new BindResult(false, BindResultType.NO_PERMISSION));
        }

        String trimmedCode = code.trim().toUpperCase();

        return isCodeExists(trimmedCode)
            .thenCompose(codeExists -> {
                if (!codeExists) {
                    return CompletableFuture.completedFuture(new BindResult(false, BindResultType.CODE_NOT_FOUND));
                }
                return CompletableFuture.completedFuture(null);
            })
            .thenCompose(result -> {
                if (result != null) return CompletableFuture.completedFuture(result);

                return plugin.getDatabaseManager().hasUsedInviteCode(playerUuid).thenCompose(hasUsed -> {
                    if (hasUsed) {
                        return CompletableFuture.completedFuture(new BindResult(false, BindResultType.ALREADY_USED));
                    }
                    return CompletableFuture.completedFuture(null);
                });
            })
            .thenCompose(result -> {
                if (result != null) return CompletableFuture.completedFuture(result);

                boolean allowVeteranToBind = plugin.getConfigManager().getConfig()
                    .getBoolean("invite_code.allow_veteran_to_bind", false);
                if (!allowVeteranToBind) {
                    String playerInviteCode = plugin.getCacheManager().getInviteCode(playerUuid);
                    if (playerInviteCode != null) {
                        return CompletableFuture.completedFuture(new BindResult(false, BindResultType.VETERAN_CANNOT_BIND));
                    }
                }
                return CompletableFuture.completedFuture(null);
            })
            .thenCompose(result -> {
                if (result != null) return CompletableFuture.completedFuture(result);

                return getInviterByCode(trimmedCode).thenCompose(inviterUuid -> {
                    if (inviterUuid != null && inviterUuid.equals(playerUuid)) {
                        return CompletableFuture.completedFuture(new BindResult(false, BindResultType.SELF_INVITE));
                    }

                    String inviterIp = inviterUuid != null ? getPlayerIp(inviterUuid) : null;

                    if (plugin.getConfigManager().getConfig().getBoolean("ip_restriction.enabled", false)
                            && plugin.getConfigManager().getConfig().getBoolean("ip_restriction.prevent_self_ip", true)
                            && inviterUuid != null
                            && inviterIp != null && inviterIp.equals(playerIp)) {
                        return CompletableFuture.completedFuture(new BindResult(false, BindResultType.SELF_INVITE));
                    }

                    return plugin.getDatabaseManager().getIpInviteCount(playerIp)
                        .thenCompose(currentCount -> determineFunctionPermissions(playerUuid, playerIp, inviterIp, currentCount))
                        .thenCompose(result2 -> {
                            if (result2 != null) return CompletableFuture.completedFuture(result2);

                            int maxInvites = plugin.getConfigManager().getConfig().getInt("invite_code.max_invites", 0);
                            if (maxInvites > 0 && inviterUuid != null) {
                                return plugin.getDatabaseManager().getPlayerData(inviterUuid).thenCompose(inviterData -> {
                                    if (inviterData != null && inviterData.totalInvites >= maxInvites) {
                                        return CompletableFuture.completedFuture(new BindResult(false, BindResultType.INVITER_LIMIT_REACHED));
                                    }
                                    return doBindInviteRecord(playerUuid, playerIp, playerName, inviterUuid, trimmedCode, player);
                                });
                            }

                            return doBindInviteRecord(playerUuid, playerIp, playerName, inviterUuid, trimmedCode, player);
                        });
                });
            })
            .exceptionally(ex -> {
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
        UNKNOWN
    }

    public static class BindResult {
        public final boolean success;
        public final BindResultType type;

        public BindResult(boolean success, BindResultType type) {
            this.success = success;
            this.type = type;
        }
    }

    public enum InviteResultType {
        SUCCESS,
        INVALID_CODE,
        ALREADY_USED,
        IP_LIMIT,
        SELF_INVITE,
        INVITER_LIMIT_REACHED,
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

    private CompletableFuture<BindResult> doBindInviteRecord(UUID playerUuid, String playerIp, String playerName, UUID inviterUuid, String trimmedCode, Player player) {
        return plugin.getDatabaseManager().updateInviteCode(playerUuid, trimmedCode)
            .thenCompose(v -> {
                plugin.getCacheManager().invalidateInviteCode(playerUuid);
                return plugin.getDatabaseManager().addInviteRecord(inviterUuid, playerUuid, playerIp, playerName);
            })
            .thenCompose(inserted -> {
                if (!Boolean.TRUE.equals(inserted)) {
                    // 防跨服/并发重复绑定记录：唯一索引拦截
                    return CompletableFuture.completedFuture(new BindResult(false, BindResultType.ALREADY_USED));
                }
                return plugin.getDatabaseManager().getPlayerData(inviterUuid)
                    .thenCompose(data -> {
                        if (data != null) {
                            int newTotal = data.totalInvites + 1;
                            return plugin.getDatabaseManager().updateInviteCount(inviterUuid, newTotal)
                                .thenAccept(v -> {
                                    plugin.getCacheManager().invalidateStats(inviterUuid);
                                    plugin.getMilestoneManager().checkMilestones(inviterUuid, newTotal);
                                })
                                .thenApply(v -> new BindResult(true, BindResultType.SUCCESS));
                        }
                        return CompletableFuture.completedFuture(new BindResult(true, BindResultType.SUCCESS));
                    })
                    .thenApply(result -> {
                        if (result.success) {
                            // 通知旧式监听器与 Bukkit 事件（全局线程）
                            plugin.getScheduler().runGlobal(() -> {
                                com.alinvite.api.ALInviteAPI.fireInviteSuccess(inviterUuid, playerUuid);
                                Bukkit.getPluginManager().callEvent(
                                    new com.alinvite.api.event.InviteBindEvent(player, inviterUuid, trimmedCode));
                            });
                            plugin.getGiftManager().giveGiftRewards(player, inviterUuid);
                        }
                        return result;
                    });
            });
    }

    private CompletableFuture<BindResult> determineFunctionPermissions(UUID playerUuid, String playerIp, String inviterIp, int currentCount) {
        Player player = Bukkit.getPlayer(playerUuid);

        if (plugin.getConfigManager().getConfig().getBoolean("ip_restriction.enabled", false)) {
            if (currentCount > 0) {
                if (player != null) {
                    plugin.getScheduler().runAtPlayer(player, () ->
                        player.sendMessage(plugin.getConfigManager().getMessage("function_restrictions.bind_failed_ip_restriction")));
                }
                return CompletableFuture.completedFuture(new BindResult(false, BindResultType.SELF_INVITE));
            }
            if (player != null) {
                plugin.getScheduler().runAtPlayer(player, () ->
                    player.sendMessage(plugin.getConfigManager().getMessage("function_restrictions.bind_success_full")));
            }
            return plugin.getDatabaseManager().updateFunctionPermissions(playerUuid, playerIp, true, true, true)
                .thenApply(v -> null);
        }

        boolean isSameIp = inviterIp != null && inviterIp.equals(playerIp);

        boolean milestoneEnabled;
        boolean rebateEnabled;
        boolean giftEnabled;

        if (isSameIp) {
            milestoneEnabled = plugin.getConfigManager().getConfig().getBoolean("ip_restriction.flexible_mode.milestone", false);
            rebateEnabled = plugin.getConfigManager().getConfig().getBoolean("ip_restriction.flexible_mode.rebate", true);
            giftEnabled = plugin.getConfigManager().getConfig().getBoolean("ip_restriction.flexible_mode.gift", false);
        } else {
            milestoneEnabled = true;
            rebateEnabled = true;
            giftEnabled = true;
        }

        if (player != null) {
            plugin.getScheduler().runAtPlayer(player, () -> {
                if (milestoneEnabled && rebateEnabled && giftEnabled) {
                    player.sendMessage(plugin.getConfigManager().getMessage("function_restrictions.bind_success_full"));
                } else {
                    StringBuilder message = new StringBuilder(plugin.getConfigManager().getMessage("function_restrictions.bind_success_limited") + "\n");

                    message.append(plugin.getConfigManager().getMessage(milestoneEnabled
                            ? "function_restrictions.bind_success_milestone_enabled"
                            : "function_restrictions.bind_success_milestone_disabled") + "\n");
                    message.append(plugin.getConfigManager().getMessage(rebateEnabled
                            ? "function_restrictions.bind_success_rebate_enabled"
                            : "function_restrictions.bind_success_rebate_disabled") + "\n");
                    message.append(plugin.getConfigManager().getMessage(giftEnabled
                            ? "function_restrictions.bind_success_gift_enabled"
                            : "function_restrictions.bind_success_gift_disabled") + "\n");

                    player.sendMessage(message.toString());
                }
            });
        }

        return plugin.getDatabaseManager().updateFunctionPermissions(playerUuid, playerIp, milestoneEnabled, rebateEnabled, giftEnabled)
            .thenApply(v -> null);
    }
}
