package com.alinvite.placeholder;

import com.alinvite.ALInvite;
import com.alinvite.database.DatabaseManager;
import com.alinvite.manager.LeaderboardManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 排行榜占位符实现
 * 提供玩家排名、玩家数值、排行榜前10名等占位符
 */
public class LeaderboardPlaceholderHook extends PlaceholderExpansion {

    private final ALInvite plugin;

    public LeaderboardPlaceholderHook(ALInvite plugin) {
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
        if (player == null) {
            return "-";
        }
        
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
        try {
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
        } catch (Exception e) {
            plugin.getLogger().warning("获取玩家排名失败: " + e.getMessage());
            return "-";
        }
    }
    
    /**
     * 获取玩家本人的数值
     */
    private String getPlayerValue(UUID uuid, String valueType) {
        try {
            DatabaseManager.PlayerData data = plugin.getDatabaseManager().getPlayerDataSync(uuid);
            if (data == null) {
                return "0";
            }
            
            return switch (valueType.toLowerCase()) {
                case "invites" -> String.valueOf(data.totalInvites);
                case "contribution" -> String.valueOf(data.contributionAmount);
                case "points" -> String.valueOf(data.totalRebatePoints);
                default -> "-";
            };
        } catch (Exception e) {
            plugin.getLogger().warning("获取玩家数值失败: " + e.getMessage());
            return "-";
        }
    }
    
    /**
     * 获取排行榜前10名玩家的信息
     */
    private String getTopPlayerValue(LeaderboardManager.LeaderboardType type, int rank, String field) {
        try {
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
        } catch (Exception e) {
            plugin.getLogger().warning("获取排行榜数据失败: " + e.getMessage());
            return "-";
        }
    }
}