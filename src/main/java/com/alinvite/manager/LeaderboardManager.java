package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.database.DatabaseManager;
import com.alinvite.utils.AsyncPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class LeaderboardManager {

    private final ALInvite plugin;
    private final DatabaseManager databaseManager;
    private boolean isShutdown = false;
    private int updateTaskId = -1;
    
    // 排行榜缓存
    private final Map<LeaderboardType, List<LeaderboardEntry>> leaderboardCache = new HashMap<>();
    
    // 排行榜类型
    public enum LeaderboardType {
        MILESTONE("milestone", "邀请人数"),
        CONTRIBUTION("contribution", "贡献返点"),
        POINTS_REBATE("points_rebate", "点券返点");
        
        private final String key;
        private final String name;
        
        LeaderboardType(String key, String name) {
            this.key = key;
            this.name = name;
        }
        
        public String getKey() {
            return key;
        }
        
        public String getName() {
            return name;
        }
        
        public static LeaderboardType fromKey(String key) {
            for (LeaderboardType type : values()) {
                if (type.key.equals(key)) {
                    return type;
                }
            }
            return null;
        }
    }
    
    // 排行榜条目
    public class LeaderboardEntry {
        private final UUID uuid;
        private final String name;
        private final double value;
        
        public LeaderboardEntry(UUID uuid, String name, double value) {
            this.uuid = uuid;
            this.name = name;
            this.value = value;
        }
        
        public UUID getUuid() {
            return uuid;
        }
        
        public String getName() {
            return name;
        }
        
        public double getValue() {
            return value;
        }
    }
    
    public LeaderboardManager(ALInvite plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        init();
    }
    
    private void init() {
        // 初始化缓存
        for (LeaderboardType type : LeaderboardType.values()) {
            leaderboardCache.put(type, new ArrayList<>());
        }
        
        // 读取配置的更新间隔（默认3600秒）
        int updateInterval = plugin.getConfigManager().getConfig().getInt("leaderboard.update_interval", 3600);
        // 转换为tick（20 tick = 1秒）
        long intervalTicks = updateInterval * 20L;

        // 启动定时更新任务（句柄登记，shutdown 时取消，杜绝 reload 后旧定时器泄漏）
        updateTaskId = plugin.getScheduler().runGlobalTimer(this::updateAllLeaderboards, 20L, Math.max(20L, intervalTicks));
    }
    
    // 更新所有排行榜
    private void updateAllLeaderboards() {
        for (LeaderboardType type : LeaderboardType.values()) {
            updateLeaderboard(type);
        }
    }
    
    // 更新指定排行榜
    public void updateLeaderboard(LeaderboardType type) {
        AsyncPool.run(() -> {
            // 检查插件是否已禁用或已关闭
            if (isShutdown || !plugin.isEnabled()) {
                return;
            }
            
            List<LeaderboardEntry> entries = new ArrayList<>();
            
            try (Connection conn = databaseManager.getConnection()) {
                // 再次检查连接是否有效
                if (conn == null || conn.isClosed()) {
                    plugin.getLogger().warning("无法获取有效的数据库连接，跳过排行榜更新");
                    return;
                }
                
                String sql = getLeaderboardSql(type);
                if (sql == null) return;
                
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            String name = rs.getString("name");
                            double value = rs.getDouble("value");
                            entries.add(new LeaderboardEntry(uuid, name, value));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("更新排行榜时出错: " + e.getMessage());
            }
            
            // 更新缓存
            leaderboardCache.put(type, entries);
            plugin.getLogger().info("更新了" + type.getName() + "总榜");
        });
    }
    
    /**
     * 关闭排行榜管理器：取消定时任务并停止异步更新
     */
    public void shutdown() {
        isShutdown = true;
        if (updateTaskId != -1) {
            plugin.getScheduler().cancel(updateTaskId);
            updateTaskId = -1;
        }
        plugin.getLogger().info("排行榜管理器已关闭");
    }
    
    // 获取排行榜SQL
    private String getLeaderboardSql(LeaderboardType type) {
        String tablePrefix = databaseManager.getTablePrefix();
        
        switch (type) {
            case MILESTONE:
                return "SELECT uuid, invite_code as name, total_invites as value FROM " + tablePrefix + "players WHERE total_invites > 0 ORDER BY total_invites DESC LIMIT 100";
            case CONTRIBUTION:
                return "SELECT uuid, invite_code as name, contribution_amount as value FROM " + tablePrefix + "players WHERE contribution_amount > 0 ORDER BY contribution_amount DESC LIMIT 100";
            case POINTS_REBATE:
                return "SELECT uuid, invite_code as name, total_rebate_points as value FROM " + tablePrefix + "players WHERE total_rebate_points > 0 ORDER BY total_rebate_points DESC LIMIT 100";
            default:
                return null;
        }
    }
    
    // 获取排行榜数据
    public List<LeaderboardEntry> getLeaderboard(LeaderboardType type) {
        return leaderboardCache.get(type);
    }
    
    // 获取排行榜变量（用于其他插件）
    public Map<String, String> getLeaderboardVariables(LeaderboardType type) {
        Map<String, String> variables = new HashMap<>();
        List<LeaderboardEntry> entries = getLeaderboard(type);
        
        // 生成类型前缀
        String typePrefix = "";
        switch (type) {
            case MILESTONE:
                typePrefix = "invite";
                break;
            case CONTRIBUTION:
                typePrefix = "contribution";
                break;
            case POINTS_REBATE:
                typePrefix = "points";
                break;
        }
        
        for (int i = 0; i < Math.min(10, entries.size()); i++) {
            LeaderboardEntry entry = entries.get(i);
            int rank = i + 1;
            
            // 玩家名字变量
            variables.put(typePrefix + "_player_" + rank, entry.getName());
            
            // 数值变量
            switch (type) {
                case MILESTONE:
                    variables.put(typePrefix + "_count_" + rank, String.valueOf((int) entry.getValue()));
                    break;
                case CONTRIBUTION:
                    variables.put(typePrefix + "_rebate_" + rank, String.valueOf(entry.getValue()));
                    break;
                case POINTS_REBATE:
                    variables.put(typePrefix + "_rebate_" + rank, String.valueOf((int) entry.getValue()));
                    break;
            }
        }
        
        // 填充空白
        for (int i = entries.size(); i < 10; i++) {
            int rank = i + 1;
            variables.put(typePrefix + "_player_" + rank, "-");
            
            switch (type) {
                case MILESTONE:
                    variables.put(typePrefix + "_count_" + rank, "0");
                    break;
                case CONTRIBUTION:
                case POINTS_REBATE:
                    variables.put(typePrefix + "_rebate_" + rank, "0");
                    break;
            }
        }
        
        return variables;
    }
    
    // 替换字符串中的排行榜变量
    public String replaceLeaderboardVariables(String input, LeaderboardType type) {
        Map<String, String> variables = getLeaderboardVariables(type);
        String result = input;
        
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        
        return result;
    }
}
