package com.alinvite.manager;

import com.alinvite.ALInvite;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class InviteManager {

    private final ALInvite plugin;
    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> generatingCodes = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public InviteManager(ALInvite plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<String> generateInviteCode(UUID uuid) {
        if (generatingCodes.contains(uuid)) {
            return CompletableFuture.completedFuture(plugin.getCacheManager().getInviteCode(uuid));
        }
        generatingCodes.add(uuid);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                int length = plugin.getConfigManager().getConfig().getInt("invite_code.length", 6);
                String charset = plugin.getConfigManager().getConfig().getString("invite_code.charset", "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789");
                String prefix = plugin.getConfigManager().getConfig().getString("invite_code.prefix", "");

                String code;
                int attempts = 0;
                do {
                    StringBuilder sb = new StringBuilder(prefix);
                    for (int i = 0; i < length; i++) {
                        sb.append(charset.charAt(random.nextInt(charset.length())));
                    }
                    code = sb.toString();
                    attempts++;
                    if (attempts > 10) {
                        plugin.getLogger().warning("生成邀请码失败，已尝试 " + attempts + " 次");
                        break;
                    }
                } while (isCodeExists(code).join());

                return code;
            } finally {
                generatingCodes.remove(uuid);
            }
        }).thenCompose(code -> {
            // 检查玩家数据是否存在
            return plugin.getDatabaseManager().getPlayerData(uuid).thenCompose(data -> {
                if (data != null) {
                    // 玩家数据存在，更新邀请码
                    return plugin.getDatabaseManager().updateInviteCode(uuid, code).thenApply(v -> code);
                } else {
                    // 玩家数据不存在，创建新数据
                    return plugin.getDatabaseManager().createPlayerData(uuid, code).thenApply(v -> code);
                }
            });
        }).thenApply(code -> {
            plugin.getCacheManager().setInviteCode(uuid, code);
            return code;
        });
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
                
                return hasUsedInviteCode(inviteeUuid).thenCompose(hasUsed -> {
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

                    // 检查邀请人是否达到最大邀请人数上限
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

    private CompletableFuture<Boolean> hasUsedInviteCode(UUID uuid) {
        return plugin.getDatabaseManager().hasUsedInviteCode(uuid);
    }

    private String getPlayerIp(UUID uuid) {
        return Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getUniqueId().equals(uuid))
            .findFirst()
            .map(p -> p.getAddress().getAddress().getHostAddress())
            .orElse(null);
    }

    /**
     * 添加邀请记录并更新邀请人计数（提取为公共方法，供 processInvite 和 bindInviteCode 复用）
     */
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
        return plugin.getCacheManager().getInviteCodeAsync(uuid).thenApply(v -> plugin.getCacheManager().getInviteCode(uuid));
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

                        // 只有当 ip_restriction.enabled=true 时，才启用 prevent_self_ip 检查
                        if (plugin.getConfigManager().getConfig().getBoolean("ip_restriction.enabled", false)) {
                            if (plugin.getConfigManager().getConfig().getBoolean("ip_restriction.prevent_self_ip", true)) {
                                if (inviterUuid != null) {
                                    if (inviterIp != null && inviterIp.equals(playerIp)) {
                                        return CompletableFuture.completedFuture(new BindResult(false, BindResultType.SELF_INVITE));
                                    }
                                }
                            }
                        }
                        
                        return plugin.getDatabaseManager().getIpInviteCount(playerIp).thenCompose(currentCount -> {
                            // 检测同IP绑定顺序并设置功能权限
                            return determineFunctionPermissions(playerUuid, playerIp, inviterIp, currentCount);
                        })
                    .thenCompose(result2 -> {
                        if (result2 != null) return CompletableFuture.completedFuture(result2);

                        // 检查邀请人是否达到最大邀请人数上限
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

    /**
     * 执行绑定邀请记录（提取为公共方法）
     */
    private CompletableFuture<BindResult> doBindInviteRecord(UUID playerUuid, String playerIp, String playerName, UUID inviterUuid, String trimmedCode, Player player) {
        return plugin.getDatabaseManager().updateInviteCode(playerUuid, trimmedCode)
            .thenCompose(v -> {
                plugin.getCacheManager().invalidateInviteCode(playerUuid);
                return plugin.getDatabaseManager().addInviteRecord(inviterUuid, playerUuid, playerIp, playerName);
            })
            .thenCompose(v -> plugin.getDatabaseManager().getPlayerData(inviterUuid))
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
            .thenApply(result3 -> {
                plugin.getGiftManager().giveGiftRewards(player, inviterUuid);
                return result3;
            });
    }

    public void startBindCodeInput(Player player) {
        player.sendMessage(plugin.getConfigManager().getMessage("bind.enter_code"));
    }

    /**
     * 检测同IP绑定顺序并设置功能权限
     */
    private CompletableFuture<BindResult> determineFunctionPermissions(UUID playerUuid, String playerIp, String inviterIp, int currentCount) {
        Player player = Bukkit.getPlayer(playerUuid);
        
        // 如果IP限制启用（enabled=true），则完全禁止同IP绑定
        if (plugin.getConfigManager().getConfig().getBoolean("ip_restriction.enabled", false)) {
            if (currentCount > 0) {
                if (player != null) {
                    player.sendMessage(plugin.getConfigManager().getMessage("function_restrictions.bind_failed_ip_restriction"));
                }
                return CompletableFuture.completedFuture(new BindResult(false, BindResultType.SELF_INVITE));
            }
            // 第一个绑定，设置所有功能为启用
            if (player != null) {
                player.sendMessage(plugin.getConfigManager().getMessage("function_restrictions.bind_success_full"));
            }
            return plugin.getDatabaseManager().updateFunctionPermissions(playerUuid, playerIp, true, true, true)
                .thenApply(v -> null);
        }
        
        // 判断是否是真正的同IP绑定（邀请者和被邀请者IP相同）
        boolean isSameIp = inviterIp != null && inviterIp.equals(playerIp);
        
        boolean milestoneEnabled;
        boolean rebateEnabled;
        boolean giftEnabled;
        
        if (isSameIp) {
            // 同IP绑定：使用 flexible_mode 配置
            milestoneEnabled = plugin.getConfigManager().getConfig().getBoolean("ip_restriction.flexible_mode.milestone", false);
            rebateEnabled = plugin.getConfigManager().getConfig().getBoolean("ip_restriction.flexible_mode.rebate", true);
            giftEnabled = plugin.getConfigManager().getConfig().getBoolean("ip_restriction.flexible_mode.gift", false);
        } else {
            // 不同IP绑定：所有功能都启用
            milestoneEnabled = true;
            rebateEnabled = true;
            giftEnabled = true;
        }

        // 发送功能权限提示
        if (player != null) {
            if (milestoneEnabled && rebateEnabled && giftEnabled) {
                player.sendMessage(plugin.getConfigManager().getMessage("function_restrictions.bind_success_full"));
            } else {
                // 根据实际配置生成动态提示消息
                StringBuilder message = new StringBuilder(plugin.getConfigManager().getMessage("function_restrictions.bind_success_limited") + "\n");
                
                if (milestoneEnabled) {
                    message.append(plugin.getConfigManager().getMessage("function_restrictions.bind_success_milestone_enabled") + "\n");
                } else {
                    message.append(plugin.getConfigManager().getMessage("function_restrictions.bind_success_milestone_disabled") + "\n");
                }
                
                if (rebateEnabled) {
                    message.append(plugin.getConfigManager().getMessage("function_restrictions.bind_success_rebate_enabled") + "\n");
                } else {
                    message.append(plugin.getConfigManager().getMessage("function_restrictions.bind_success_rebate_disabled") + "\n");
                }
                
                if (giftEnabled) {
                    message.append(plugin.getConfigManager().getMessage("function_restrictions.bind_success_gift_enabled") + "\n");
                } else {
                    message.append(plugin.getConfigManager().getMessage("function_restrictions.bind_success_gift_disabled") + "\n");
                }
                
                player.sendMessage(message.toString());
            }
        }

        return plugin.getDatabaseManager().updateFunctionPermissions(playerUuid, playerIp, milestoneEnabled, rebateEnabled, giftEnabled)
            .thenApply(v -> null);
    }
}
