package com.alinvite.placeholder;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.manager.GiftManager;
import com.alinvite.manager.MilestoneManager;
import com.alinvite.manager.LeaderboardManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlaceholderHook extends PlaceholderExpansion {

    private final ALInvite plugin;

    public PlaceholderHook(ALInvite plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "alinvite";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Allen_Linong";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        UUID uuid = player.getUniqueId();

        switch (params.toLowerCase()) {
            case "code" -> {
                String code = plugin.getCacheManager().getInviteCode(uuid,
                        plugin.getDatabaseManager()::getInviteCodeByPlayerSync);
                return code != null ? code : "N/A";
            }

            case "total" -> {
                Integer total = plugin.getCacheManager().getStats(uuid, id -> {
                    var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                    return data != null ? data.totalInvites : 0;
                });
                return String.valueOf(total);
            }

            case "next_milestone" -> {
                Map<Integer, MilestoneManager.Milestone> milestones = plugin.getMilestoneManager().getMilestones();
                Integer currentTotal = plugin.getCacheManager().getStats(uuid, id -> {
                    var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                    return data != null ? data.totalInvites : 0;
                });

                for (Map.Entry<Integer, MilestoneManager.Milestone> entry : milestones.entrySet()) {
                    if (entry.getKey() > currentTotal) {
                        return String.valueOf(entry.getKey());
                    }
                }
                return "MAX";
            }

            case "next_milestone_name" -> {
                Map<Integer, MilestoneManager.Milestone> milestones = plugin.getMilestoneManager().getMilestones();
                Integer currentTotal = plugin.getCacheManager().getStats(uuid, id -> {
                    var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                    return data != null ? data.totalInvites : 0;
                });

                for (Map.Entry<Integer, MilestoneManager.Milestone> entry : milestones.entrySet()) {
                    if (entry.getKey() > currentTotal) {
                        return entry.getValue().name;
                    }
                }
                return "MAX";
            }

            case "gift_name" -> {
                String giftId = plugin.getCacheManager().getGiftId(uuid,
                        plugin.getDatabaseManager()::getGiftIdSync);

                if (giftId == null) {
                    return "无";
                }

                GiftManager.GiftConfig gift = plugin.getGiftManager().getGift(giftId);
                return gift != null ? ConfigManager.colorize(gift.name) : "无";
            }

            case "has_gift" -> {
                String giftId = plugin.getCacheManager().getGiftId(uuid,
                        plugin.getDatabaseManager()::getGiftIdSync);
                return giftId != null ? "true" : "false";
            }

            case "gift_status" -> {
                String giftId = plugin.getCacheManager().getGiftId(uuid,
                        plugin.getDatabaseManager()::getGiftIdSync);
                
                if (giftId == null) {
                    String defaultGiftId = plugin.getConfigManager().getConfig()
                        .getString("new_player_reward.default_gift_id", "default");
                    GiftManager.GiftConfig defaultGift = plugin.getGiftManager().getGift(defaultGiftId);
                    if (defaultGift != null && defaultGift.durationDays == 0) {
                        return "永久";
                    } else if (defaultGift != null) {
                        return defaultGift.durationDays + "天";
                    }
                    return "未购买";
                }
                
                // 使用新的 getGiftPurchaseTimeById 获取该礼包单独的购买时间
                long purchaseTime = plugin.getCacheManager().getGiftPurchaseTime(uuid, giftId,
                        (id, gid) -> plugin.getDatabaseManager().getGiftPurchaseTimeByIdSync(id, gid));
                GiftManager.GiftConfig gift = plugin.getGiftManager().getGift(giftId);
                if (gift == null) {
                    return "未购买";
                }
                
                if (gift.durationDays == 0) {
                    return "已购买";
                }
                
                if (purchaseTime == 0) {
                    return gift.durationDays + "天";
                }
                
                long currentTime = System.currentTimeMillis();
                long expirationTime = purchaseTime + (gift.durationDays * 24L * 60L * 60L * 1000L);
                
                if (currentTime > expirationTime) {
                    return "已过期";
                } else {
                    return "已购买";
                }
            }

            case "gift_remaining_days" -> {
                String giftId = plugin.getCacheManager().getGiftId(uuid,
                        plugin.getDatabaseManager()::getGiftIdSync);
                
                if (giftId == null) {
                    String defaultGiftId = plugin.getConfigManager().getConfig()
                        .getString("new_player_reward.default_gift_id", "default");
                    GiftManager.GiftConfig defaultGift = plugin.getGiftManager().getGift(defaultGiftId);
                    if (defaultGift != null && defaultGift.durationDays == 0) {
                        return "永久";
                    } else if (defaultGift != null) {
                        return defaultGift.durationDays + "天";
                    }
                    return "未购买";
                }
                
                // 使用新的 getGiftPurchaseTimeById 获取该礼包单独的购买时间
                long purchaseTime = plugin.getCacheManager().getGiftPurchaseTime(uuid, giftId,
                        (id, gid) -> plugin.getDatabaseManager().getGiftPurchaseTimeByIdSync(id, gid));
                GiftManager.GiftConfig gift = plugin.getGiftManager().getGift(giftId);
                if (gift == null) {
                    return "未购买";
                }
                
                if (gift.durationDays == 0) {
                    return "永久";
                }
                
                if (purchaseTime == 0) {
                    return gift.durationDays + "天";
                }
                
                long currentTime = System.currentTimeMillis();
                long expirationTime = purchaseTime + (gift.durationDays * 24L * 60L * 60L * 1000L);
                
                if (currentTime > expirationTime) {
                    return "0天";
                } else {
                    long remainingTime = expirationTime - currentTime;
                    int remainingDays = (int) (remainingTime / (24L * 60L * 60L * 1000L));
                    return remainingDays + "天";
                }
            }

            case "bind_status" -> {
                UUID inviterUuid = plugin.getCacheManager().getInviter(uuid,
                        plugin.getDatabaseManager()::getInviterSync);
                return inviterUuid != null ? "已绑定" : "未绑定";
            }

            case "inviter_name" -> {
                UUID inviterUuid = plugin.getCacheManager().getInviter(uuid,
                        plugin.getDatabaseManager()::getInviterSync);
                if (inviterUuid == null) {
                    return "无";
                }
                var data = plugin.getDatabaseManager().getPlayerDataSync(inviterUuid);
                if (data == null) {
                    return "无";
                }
                Player onlineInviter = plugin.getServer().getPlayer(inviterUuid);
                if (onlineInviter != null && onlineInviter.isOnline()) {
                    return onlineInviter.getName();
                }
                return data.inviteCode != null ? data.inviteCode : "未知";
            }

            case "total_invites" -> {
                Integer total = plugin.getCacheManager().getStats(uuid, id -> {
                    var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                    return data != null ? data.totalInvites : 0;
                });
                return String.valueOf(total);
            }

            case "remaining_for_next_milestone" -> {
                Map<Integer, MilestoneManager.Milestone> milestones = plugin.getMilestoneManager().getMilestones();
                Integer currentTotal = plugin.getCacheManager().getStats(uuid, id -> {
                    var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                    return data != null ? data.totalInvites : 0;
                });

                for (Map.Entry<Integer, MilestoneManager.Milestone> entry : milestones.entrySet()) {
                    if (entry.getKey() > currentTotal) {
                        return String.valueOf(entry.getKey() - currentTotal);
                    }
                }
                return "0";
            }

            default -> {
                if (params.startsWith("milestone_")) {
                    String milestoneKey = params.substring("milestone_".length());
                    try {
                        int milestoneNum = Integer.parseInt(milestoneKey);
                        MilestoneManager.Milestone milestone = plugin.getMilestoneManager().getMilestone(milestoneNum);
                        if (milestone != null) {
                            Integer currentTotal = plugin.getCacheManager().getStats(uuid, id -> {
                                var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                                return data != null ? data.totalInvites : 0;
                            });
                            return currentTotal >= milestoneNum ? "true" : "false";
                        }
                    } catch (NumberFormatException ignored) {}
                }
                
                // 排行榜占位符处理
                return handleLeaderboardPlaceholders(player, params);
            }
        }
    }
    
    /**
     * 处理排行榜相关的占位符
     */
    private String handleLeaderboardPlaceholders(Player player, String params) {
        UUID uuid = player.getUniqueId();
        
        // 玩家本人排名占位符
        if (params.startsWith("rank_")) {
            String rankType = params.substring("rank_".length());
            LeaderboardManager.LeaderboardType type = getLeaderboardType(rankType);
            if (type != null) {
                return getPlayerRank(uuid, type);
            }
        }
        
        // 玩家本人数值占位符
        if (params.startsWith("my_")) {
            String valueType = params.substring("my_".length());
            return getPlayerValue(uuid, valueType);
        }
        
        // 排行榜前10名占位符
        if (params.startsWith("top_")) {
            String[] parts = params.substring("top_".length()).split("_");
            if (parts.length >= 3) {
                String typeStr = parts[0];
                String rankStr = parts[1];
                String field = parts[2];
                
                LeaderboardManager.LeaderboardType type = getLeaderboardType(typeStr);
                if (type != null) {
                    try {
                        int rank = Integer.parseInt(rankStr);
                        if (rank >= 1 && rank <= 10) {
                            return getTopPlayerValue(type, rank, field);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        
        return null;
    }
    
    /**
     * 获取排行榜类型
     */
    private LeaderboardManager.LeaderboardType getLeaderboardType(String typeStr) {
        return switch (typeStr.toLowerCase()) {
            case "invite" -> LeaderboardManager.LeaderboardType.MILESTONE;
            case "contribution" -> LeaderboardManager.LeaderboardType.CONTRIBUTION;
            case "points" -> LeaderboardManager.LeaderboardType.POINTS_REBATE;
            default -> null;
        };
    }
    
    /**
     * 获取玩家在排行榜中的排名
     */
    private String getPlayerRank(UUID uuid, LeaderboardManager.LeaderboardType type) {
        List<LeaderboardManager.LeaderboardEntry> entries = plugin.getLeaderboardManager().getLeaderboard(type);
        if (entries == null || entries.isEmpty()) {
            return "-";
        }
        
        for (int i = 0; i < entries.size(); i++) {
            LeaderboardManager.LeaderboardEntry entry = entries.get(i);
            if (entry.getUuid().equals(uuid)) {
                return String.valueOf(i + 1);
            }
        }
        
        return "-";
    }
    
    /**
     * 获取玩家本人的数值
     */
    private String getPlayerValue(UUID uuid, String valueType) {
        return switch (valueType.toLowerCase()) {
            case "invites" -> {
                Integer total = plugin.getCacheManager().getStats(uuid, id -> {
                    var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                    return data != null ? data.totalInvites : 0;
                });
                yield String.valueOf(total);
            }
            case "contribution" -> {
                double contribution = plugin.getCacheManager().getContribution(uuid,
                        plugin.getDatabaseManager()::getContributionAmountSync);
                yield String.valueOf(contribution);
            }
            case "points" -> {
                double rebate = plugin.getCacheManager().getTotalRebate(uuid,
                        plugin.getDatabaseManager()::getTotalRebateAmountSync);
                yield String.valueOf((int) rebate);
            }
            default -> "-";
        };
    }
    
    /**
     * 获取排行榜前10名玩家的信息
     */
    private String getTopPlayerValue(LeaderboardManager.LeaderboardType type, int rank, String field) {
        List<LeaderboardManager.LeaderboardEntry> entries = plugin.getLeaderboardManager().getLeaderboard(type);
        if (entries == null || entries.size() < rank) {
            return "-";
        }
        
        LeaderboardManager.LeaderboardEntry entry = entries.get(rank - 1);
        
        return switch (field.toLowerCase()) {
            case "player" -> entry.getName();
            case "count" -> type == LeaderboardManager.LeaderboardType.MILESTONE ? 
                String.valueOf((int) entry.getValue()) : "-";
            case "rebate" -> type != LeaderboardManager.LeaderboardType.MILESTONE ? 
                String.valueOf(entry.getValue()) : "-";
            default -> "-";
        };
    }
}
