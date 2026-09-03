package com.alinvite.database;

import com.alinvite.ALInvite;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.entity.Player;

import java.io.File;
import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {

    private final ALInvite plugin;
    private final Map<UUID, Object> milestoneClaimLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private String tablePrefix;
    private HikariDataSource dataSource;

    public DatabaseManager(ALInvite plugin) {
        this.plugin = plugin;
    }

    public void init() {
        String type = plugin.getConfigManager().getConfig().getString("database.type", "sqlite");
        this.tablePrefix = plugin.getConfigManager().getConfig().getString("database.table_prefix", "alinvite_");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("ALInvite-Pool");

        if (type.equalsIgnoreCase("mysql")) {
            String host = plugin.getConfigManager().getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfigManager().getConfig().getInt("database.mysql.port", 3306);
            String database = plugin.getConfigManager().getConfig().getString("database.mysql.database", "minecraft");
            String user = plugin.getConfigManager().getConfig().getString("database.mysql.user", "root");
            String password = plugin.getConfigManager().getConfig().getString("database.mysql.password", "");
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(password);
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setConnectionTimeout(30000);
        } else {
            String dbFile = plugin.getConfigManager().getConfig().getString("database.sqlite_file", "data.db");
            File file = new File(plugin.getDataFolder(), dbFile);
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikariConfig.setMaximumPoolSize(5);
            hikariConfig.setConnectionTimeout(5000);
            hikariConfig.setMinimumIdle(1);
        }

        this.dataSource = new HikariDataSource(hikariConfig);

        createTables();
    }

    private int reconnectAttempts = 0;

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            boolean autoReconnect = plugin.getConfigManager().getConfig().getBoolean("database.auto_reconnect", true);
            if (!autoReconnect) {
                throw new SQLException("数据库连接池已关闭，且未启用自动重连");
            }

            plugin.getLogger().warning("数据库连接池已关闭，尝试重新初始化...");

            int maxAttempts = plugin.getConfigManager().getConfig().getInt("database.max_reconnect_attempts", 3);
            long delayMs = plugin.getConfigManager().getConfig().getLong("database.reconnect_delay_ms", 5000);

            try {
                if (maxAttempts > 0 && reconnectAttempts >= maxAttempts) {
                    throw new SQLException("数据库连接池重连次数已达到上限 (" + maxAttempts + "次)");
                }

                if (reconnectAttempts > 0 && delayMs > 0) {
                    Thread.sleep(delayMs);
                }

                reconnectAttempts++;
                init();
                reconnectAttempts = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("数据库重连被中断", e);
            } catch (Exception e) {
                plugin.getLogger().severe("数据库连接池重新初始化失败 (尝试 " + reconnectAttempts + " 次): " + e.getMessage());
                throw new SQLException("数据库连接池已关闭且无法重新初始化", e);
            }
        }
        return dataSource.getConnection();
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    private void createTables() {
        String type = plugin.getConfigManager().getConfig().getString("database.type", "sqlite");
        boolean isMySQL = type.equalsIgnoreCase("mysql");

        String autoIncrementSyntax = isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";

        String playersTable = """
            CREATE TABLE IF NOT EXISTS `{prefix}players` (
                `uuid` VARCHAR(36) PRIMARY KEY,
                `invite_code` VARCHAR(16) NOT NULL UNIQUE,
                `total_invites` INT NOT NULL DEFAULT 0,
                `claimed_milestones` TEXT,
                `announced_milestones` TEXT,
                `gift_id` VARCHAR(32),
                `purchased_gifts` TEXT,
                `gift_purchase_time` BIGINT,
                `money` REAL NOT NULL DEFAULT 0,
                `contribution_amount` REAL NOT NULL DEFAULT 0,
                `total_rebate_amount` REAL NOT NULL DEFAULT 0,
                `total_rebate_points` REAL NOT NULL DEFAULT 0,
                `bind_ip` VARCHAR(45),
                `milestone_enabled` INT NOT NULL DEFAULT 1,
                `rebate_enabled` INT NOT NULL DEFAULT 1,
                `gift_enabled` INT NOT NULL DEFAULT 1,
                `last_invite_time` BIGINT,
                `last_code_change` BIGINT,
                `last_permission_group` VARCHAR(32),
                `created_at` BIGINT NOT NULL
            )
            """;

        String recordsTable = """
            CREATE TABLE IF NOT EXISTS `{prefix}records` (
                `id` INTEGER PRIMARY KEY {autoIncrement},
                `inviter_uuid` VARCHAR(36) NOT NULL,
                `invitee_uuid` VARCHAR(36) NOT NULL,
                `invitee_ip` VARCHAR(45) NOT NULL,
                `invitee_name` VARCHAR(16) NOT NULL,
                `claimed_permission_groups` TEXT,
                `invited_at` BIGINT NOT NULL
            )
            """.replace("{autoIncrement}", autoIncrementSyntax);

        String announcementsTable = """
            CREATE TABLE IF NOT EXISTS `{prefix}announcements` (
                `id` INTEGER PRIMARY KEY {autoIncrement},
                `server_id` VARCHAR(64) NOT NULL DEFAULT 'all',
                `message` TEXT NOT NULL,
                `created_at` BIGINT NOT NULL,
                `broadcasted` BOOLEAN DEFAULT FALSE,
                `broadcasted_at` BIGINT DEFAULT NULL
            )
            """.replace("{autoIncrement}", autoIncrementSyntax);

        String pendingMilestonesTable = """
            CREATE TABLE IF NOT EXISTS `{prefix}pending_milestones` (
                `id` INTEGER PRIMARY KEY {autoIncrement},
                `player_uuid` VARCHAR(36) NOT NULL,
                `milestone_key` VARCHAR(16) NOT NULL,
                `created_at` BIGINT NOT NULL,
                UNIQUE(`player_uuid`, `milestone_key`)
            )
            """.replace("{autoIncrement}", autoIncrementSyntax);

        executeUpdate(playersTable.replace("{prefix}", tablePrefix));
        executeUpdate(recordsTable.replace("{prefix}", tablePrefix));
        executeUpdate(announcementsTable.replace("{prefix}", tablePrefix));
        executeUpdate(pendingMilestonesTable.replace("{prefix}", tablePrefix));

        String pointsRebateTable = """
            CREATE TABLE IF NOT EXISTS `{prefix}points_rebate` (
                `id` INTEGER PRIMARY KEY {autoIncrement},
                `transaction_key` VARCHAR(128) NOT NULL UNIQUE,
                `player_uuid` VARCHAR(36) NOT NULL,
                `amount` DECIMAL(10,2) NOT NULL,
                `inviter_uuid` VARCHAR(36),
                `rebate_amount` DECIMAL(10,2),
                `created_at` BIGINT NOT NULL,
                `processed_at` BIGINT DEFAULT NULL,
                `status` VARCHAR(16) DEFAULT 'PENDING'
            )
            """.replace("{autoIncrement}", autoIncrementSyntax);

        executeUpdate(pointsRebateTable.replace("{prefix}", tablePrefix));

        updateTableStructure();

        createIndexes();
    }

    private void createIndexes() {
        plugin.getLogger().info("正在创建数据库索引...");

        String type = plugin.getConfigManager().getConfig().getString("database.type", "sqlite");
        boolean isMySQL = type.equalsIgnoreCase("mysql");

        Map<String, String[]> indexes = new HashMap<>();
        indexes.put("idx_" + tablePrefix + "players_invite_code", new String[]{tablePrefix + "players", "invite_code"});
        indexes.put("idx_" + tablePrefix + "players_total_invites", new String[]{tablePrefix + "players", "total_invites"});
        indexes.put("idx_" + tablePrefix + "records_inviter_uuid", new String[]{tablePrefix + "records", "inviter_uuid"});
        indexes.put("idx_" + tablePrefix + "records_invitee_uuid", new String[]{tablePrefix + "records", "invitee_uuid"});
        indexes.put("idx_" + tablePrefix + "records_invited_at", new String[]{tablePrefix + "records", "invited_at"});
        indexes.put("idx_" + tablePrefix + "points_rebate_transaction_key", new String[]{tablePrefix + "points_rebate", "transaction_key"});
        indexes.put("idx_" + tablePrefix + "points_rebate_player_uuid", new String[]{tablePrefix + "points_rebate", "player_uuid"});
        indexes.put("idx_" + tablePrefix + "points_rebate_created_at", new String[]{tablePrefix + "points_rebate", "created_at"});
        indexes.put("idx_" + tablePrefix + "announcements_created_at", new String[]{tablePrefix + "announcements", "created_at"});
        indexes.put("idx_" + tablePrefix + "pending_milestones_player_uuid", new String[]{tablePrefix + "pending_milestones", "player_uuid"});

        for (Map.Entry<String, String[]> entry : indexes.entrySet()) {
            String indexName = entry.getKey();
            String tableName = entry.getValue()[0];
            String columnName = entry.getValue()[1];

            if (isMySQL) {
                try (Connection conn = dataSource.getConnection()) {
                    String checkIndexSql = "SHOW INDEX FROM " + tableName + " WHERE Key_name = ?";
                    try (PreparedStatement ps = conn.prepareStatement(checkIndexSql)) {
                        ps.setString(1, indexName);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                String createIndexSql = "CREATE INDEX " + indexName + " ON " + tableName + "(" + columnName + ")";
                                executeUpdate(createIndexSql);
                            }
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().warning("创建索引 " + indexName + " 时出错: " + e.getMessage());
                }
            } else {
                String createIndexSql = "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + "(" + columnName + ")";
                executeUpdate(createIndexSql);
            }
        }

        plugin.getLogger().info("数据库索引创建完成！");
    }

    private void updateTableStructure() {
        String type = plugin.getConfigManager().getConfig().getString("database.type", "sqlite");
        boolean isMySQL = type.equalsIgnoreCase("mysql");

        try {
            boolean columnExists = checkColumnExists("players", "contribution_amount", isMySQL);

            if (!columnExists) {
                String alterTableSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN contribution_amount REAL NOT NULL DEFAULT 0";

                if (isMySQL) {
                    alterTableSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN contribution_amount DECIMAL(10,2) NOT NULL DEFAULT 0";
                }

                executeUpdate(alterTableSql);
                plugin.getLogger().info("已为数据库表添加 contribution_amount 字段");
            }

            boolean milestoneEnabledExists = checkColumnExists("players", "milestone_enabled", isMySQL);
            boolean rebateEnabledExists = checkColumnExists("players", "rebate_enabled", isMySQL);
            boolean giftEnabledExists = checkColumnExists("players", "gift_enabled", isMySQL);
            boolean bindIpExists = checkColumnExists("players", "bind_ip", isMySQL);

            if (!milestoneEnabledExists) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN milestone_enabled BOOLEAN NOT NULL DEFAULT 1";
                if (isMySQL) alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN milestone_enabled TINYINT(1) NOT NULL DEFAULT 1";
                executeUpdate(alterSql);
                plugin.getLogger().info("已为数据库表添加 milestone_enabled 字段");
            }

            if (!rebateEnabledExists) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN rebate_enabled BOOLEAN NOT NULL DEFAULT 1";
                if (isMySQL) alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN rebate_enabled TINYINT(1) NOT NULL DEFAULT 1";
                executeUpdate(alterSql);
                plugin.getLogger().info("已为数据库表添加 rebate_enabled 字段");
            }

            if (!giftEnabledExists) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN gift_enabled BOOLEAN NOT NULL DEFAULT 1";
                if (isMySQL) alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN gift_enabled TINYINT(1) NOT NULL DEFAULT 1";
                executeUpdate(alterSql);
                plugin.getLogger().info("已为数据库表添加 gift_enabled 字段");
            }

            if (!bindIpExists) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN bind_ip VARCHAR(45)";
                executeUpdate(alterSql);
                plugin.getLogger().info("已为数据库表添加 bind_ip 字段");
            }

            boolean giftPurchaseTimeExists = checkColumnExists("players", "gift_purchase_time", isMySQL);
            if (!giftPurchaseTimeExists) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN gift_purchase_time BIGINT";
                executeUpdate(alterSql);
                plugin.getLogger().info("已为数据库表添加 gift_purchase_time 字段");
            }

            boolean totalRebateAmountExists = checkColumnExists("players", "total_rebate_amount", isMySQL);
            if (!totalRebateAmountExists) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN total_rebate_amount REAL NOT NULL DEFAULT 0";
                if (isMySQL) alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN total_rebate_amount DECIMAL(10,2) NOT NULL DEFAULT 0";
                executeUpdate(alterSql);
                plugin.getLogger().info("已为数据库表添加 total_rebate_amount 字段");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("更新数据库表结构失败: " + e.getMessage());
        }
    }

    private boolean checkColumnExists(String tableName, String columnName, boolean isMySQL) {
        try {
            String checkColumnSql;
            if (isMySQL) {
                checkColumnSql = "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
            } else {
                checkColumnSql = "PRAGMA table_info(\"" + tablePrefix + tableName + "\")";
            }

            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(checkColumnSql)) {

                if (isMySQL) {
                    stmt.setString(1, tablePrefix + tableName);
                    stmt.setString(2, columnName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt(1) > 0;
                        }
                    }
                } else {
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String existingColumnName = rs.getString("name");
                            if (columnName.equals(existingColumnName)) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("检查字段是否存在失败: " + e.getMessage());
        }

        return false;
    }

    private int executeUpdate(String sql) {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            plugin.getLogger().severe("SQL执行失败: " + e.getMessage());
            plugin.getLogger().severe("SQL语句: " + sql);
            e.printStackTrace();
            return -1;
        }
    }

    public CompletableFuture<PlayerData> getPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        PlayerData data = new PlayerData();
                        data.uuid = uuid;
                        data.inviteCode = rs.getString("invite_code");
                        data.totalInvites = rs.getInt("total_invites");
                        data.claimedMilestones = rs.getString("claimed_milestones");
                        data.announcedMilestones = rs.getString("announced_milestones");
                        data.giftId = rs.getString("gift_id");
                        data.purchasedGifts = rs.getString("purchased_gifts");
                        data.money = rs.getDouble("money");
                        data.lastInviteTime = rs.getLong("last_invite_time");
                        data.lastCodeChange = rs.getLong("last_code_change");
                        data.lastPermissionGroup = rs.getString("last_permission_group");
                        data.createdAt = rs.getLong("created_at");
                        data.milestoneEnabled = rs.getBoolean("milestone_enabled");
                        data.rebateEnabled = rs.getBoolean("rebate_enabled");
                        data.giftEnabled = rs.getBoolean("gift_enabled");
                        data.contributionAmount = rs.getDouble("contribution_amount");
                        data.totalRebatePoints = rs.getDouble("total_rebate_points");
                        data.giftPurchaseTime = rs.getLong("gift_purchase_time");
                        return data;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取玩家数据失败: " + e.getMessage());
                plugin.getLogger().severe("SQL语句: " + sql);
                plugin.getLogger().severe("玩家UUID: " + uuid);
                e.printStackTrace();
            }
            return null;
        });
    }

    public CompletableFuture<Void> createPlayerData(UUID uuid, String inviteCode) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO " + tablePrefix + "players (uuid, invite_code, total_invites, created_at) VALUES (?, ?, 0, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, inviteCode);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("创建玩家数据失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<String> getInviteCodeByPlayer(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT invite_code FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("invite_code");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取邀请码失败: " + e.getMessage());
            }
            return null;
        });
    }

    public CompletableFuture<String> getPlayerByInviteCode(String code) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT uuid FROM " + tablePrefix + "players WHERE invite_code = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, code);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("uuid");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("通过邀请码获取玩家失败: " + e.getMessage());
            }
            return null;
        });
    }

    public CompletableFuture<UUID> getInviterUUIDByInviteCode(String code) {
        return CompletableFuture.supplyAsync(() -> {
            String uuid = getPlayerByInviteCode(code).join();
            return uuid != null ? UUID.fromString(uuid) : null;
        });
    }

    public CompletableFuture<Boolean> isInviteCodeExists(String code) {
        return CompletableFuture.supplyAsync(() -> {
            String uuid = getPlayerByInviteCode(code).join();
            return uuid != null;
        });
    }

    public CompletableFuture<Boolean> syncAnnouncements() {
        return CompletableFuture.supplyAsync(() -> {
            return true;
        });
    }

    public CompletableFuture<UUID> getInviterByInvitee(UUID inviteeUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT inviter_uuid FROM " + tablePrefix + "records WHERE invitee_uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, inviteeUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("inviter_uuid"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取邀请人失败: " + e.getMessage());
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> hasUsedInviteCode(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM " + tablePrefix + "records WHERE invitee_uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("检查是否已使用邀请码失败: " + e.getMessage());
            }
            return false;
        });
    }

    public CompletableFuture<Integer> getIpInviteCount(String ip) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM " + tablePrefix + "records WHERE invitee_ip = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, ip);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取IP邀请数失败: " + e.getMessage());
            }
            return 0;
        });
    }

    public CompletableFuture<UUID> getInviter(UUID inviteeUuid) {
        return getInviterByInvitee(inviteeUuid);
    }

    public CompletableFuture<Void> recordInvite(UUID inviterUuid, UUID inviteeUuid, String inviteeIp, String inviteeName) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO " + tablePrefix + "records (inviter_uuid, invitee_uuid, invitee_ip, invitee_name, invited_at) VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, inviterUuid.toString());
                stmt.setString(2, inviteeUuid.toString());
                stmt.setString(3, inviteeIp);
                stmt.setString(4, inviteeName);
                stmt.setLong(5, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("记录邀请失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> addInviteRecord(UUID inviterUuid, UUID inviteeUuid, String inviteeIp, String inviteeName) {
        return recordInvite(inviterUuid, inviteeUuid, inviteeIp, inviteeName);
    }

    public CompletableFuture<Void> updateTotalInvites(UUID uuid, int total) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET total_invites = ?, last_invite_time = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, total);
                stmt.setLong(2, System.currentTimeMillis());
                stmt.setString(3, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新邀请次数失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> updateInviteCount(UUID uuid, int total) {
        return updateTotalInvites(uuid, total);
    }

    public CompletableFuture<Void> updatePermissionGroup(UUID uuid, String group) {
        return updateLastPermissionGroup(uuid, group);
    }

    public CompletableFuture<String> getClaimedMilestones(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT claimed_milestones FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("claimed_milestones");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取已领取里程碑失败: " + e.getMessage());
            }
            return "[]";
        });
    }

    public CompletableFuture<Void> claimMilestone(UUID uuid, String milestoneId) {
        return CompletableFuture.runAsync(() -> {
            Object claimLock = milestoneClaimLocks.computeIfAbsent(uuid, ignored -> new Object());
            synchronized (claimLock) {
                Set<String> claimedSet = new HashSet<>();
                String selectSql = "SELECT claimed_milestones FROM " + tablePrefix + "players WHERE uuid = ?";
                String updateSql = "UPDATE " + tablePrefix + "players SET claimed_milestones = ? WHERE uuid = ?";
                try (Connection conn = getConnection();
                     PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                    selectStmt.setString(1, uuid.toString());
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (rs.next()) {
                            String claimed = rs.getString("claimed_milestones");
                            if (claimed != null && !claimed.trim().isEmpty() && !claimed.equals("[]")) {
                                String[] parts = claimed.replace("[", "").replace("]", "").replace("\"", "").split(",");
                                for (String part : parts) {
                                    String claimedId = part.trim();
                                    if (!claimedId.isEmpty()) {
                                        claimedSet.add(claimedId);
                                    }
                                }
                            }
                        }
                    }
                    claimedSet.add(milestoneId);
                    String newClaimed = "[\"" + String.join("\",\"", claimedSet) + "\"]";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, newClaimed);
                        updateStmt.setString(2, uuid.toString());
                        updateStmt.executeUpdate();
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("Failed to save milestone claim: " + e.getMessage());
                    throw new RuntimeException("Failed to save milestone claim", e);
                }
            }
        });
    }

    public CompletableFuture<String> getAnnouncedMilestones(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT announced_milestones FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("announced_milestones");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取已公告里程碑失败: " + e.getMessage());
            }
            return "[]";
        });
    }

    public CompletableFuture<Void> addAnnouncedMilestone(UUID uuid, String milestoneId) {
        return CompletableFuture.runAsync(() -> {
            String current = null;
            // 直接查询，不调用 getAnnouncedMilestones.join() 避免嵌套线程
            String selectSql = "SELECT announced_milestones FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getString("announced_milestones");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取已公告里程碑失败: " + e.getMessage());
            }
            
            Set<String> announcedSet = new HashSet<>();
            if (current != null && !current.trim().isEmpty() && !current.equals("[]")) {
                try {
                    String[] parts = current.replace("[", "").replace("]", "").replace("\"", "").split(",");
                    for (String part : parts) {
                        part = part.trim();
                        if (!part.isEmpty()) {
                            announcedSet.add(part);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("解析已公告里程碑失败，重置为空列表: " + current);
                    announcedSet = new HashSet<>();
                }
            }
            announcedSet.add(milestoneId);
            String newValue = "[\"" + String.join("\",\"", announcedSet) + "\"]";
            String updateSql = "UPDATE " + tablePrefix + "players SET announced_milestones = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, newValue);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("添加已公告里程碑失败: " + e.getMessage());
                plugin.getLogger().severe("SQL语句: " + updateSql);
                plugin.getLogger().severe("玩家UUID: " + uuid);
                plugin.getLogger().severe("里程碑ID: " + milestoneId);
                e.printStackTrace();
            }
        });
    }

    public CompletableFuture<Void> addPendingMilestone(UUID uuid, String milestoneKey) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO " + tablePrefix + "pending_milestones (player_uuid, milestone_key, created_at) VALUES (?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, milestoneKey);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("添加待领取里程碑失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<List<String>> getPendingMilestones(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> milestones = new ArrayList<>();
            String sql = "SELECT milestone_key FROM " + tablePrefix + "pending_milestones WHERE player_uuid = ? ORDER BY created_at ASC";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        milestones.add(rs.getString("milestone_key"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取待领取里程碑失败: " + e.getMessage());
            }
            return milestones;
        });
    }

    public CompletableFuture<Void> removePendingMilestone(UUID uuid, String milestoneKey) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM " + tablePrefix + "pending_milestones WHERE player_uuid = ? AND milestone_key = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, milestoneKey);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("删除待领取里程碑失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<String> getGiftId(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT gift_id FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("gift_id");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取礼包ID失败: " + e.getMessage());
            }
            return null;
        });
    }

    public CompletableFuture<Long> getGiftPurchaseTime(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT gift_purchase_time FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("gift_purchase_time");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取礼包购买时间失败: " + e.getMessage());
            }
            return 0L;
        });
    }

    public CompletableFuture<Void> setGiftId(UUID uuid, String giftId) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET gift_id = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, giftId);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("设置礼包ID失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> setGiftPurchaseTime(UUID uuid, long purchaseTime) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET gift_purchase_time = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, purchaseTime);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("设置礼包购买时间失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> updateInviteCode(UUID uuid, String newCode) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET invite_code = ?, last_code_change = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newCode);
                stmt.setLong(2, System.currentTimeMillis());
                stmt.setString(3, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新邀请码失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> clearInviteCode(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET invite_code = ?, last_code_change = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, "");
                stmt.setLong(2, System.currentTimeMillis());
                stmt.setString(3, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("清除邀请码失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> addPurchasedGift(UUID uuid, String giftId) {
        return CompletableFuture.runAsync(() -> {
            String sql = "SELECT purchased_gifts FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    String current = null;
                    if (rs.next()) {
                        current = rs.getString("purchased_gifts");
                    }
                    if (current == null || current.trim().isEmpty()) {
                        current = "[]";
                    }
                    Map<String, Long> purchasedGifts = new HashMap<>();
                    if (!current.equals("[]")) {
                        try {
                            // 解析格式: ["gift1:123456", "gift2:789012"]
                            String[] gifts = current.replace("[", "").replace("]", "").replace("\"", "").split(",");
                            for (String gift : gifts) {
                                gift = gift.trim();
                                if (!gift.isEmpty()) {
                                    String[] parts = gift.split(":");
                                    if (parts.length >= 2) {
                                        String gId = parts[0];
                                        long time = Long.parseLong(parts[1]);
                                        purchasedGifts.put(gId, time);
                                    } else if (parts.length == 1) {
                                        // 兼容旧格式
                                        purchasedGifts.put(parts[0], System.currentTimeMillis());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("解析已购买礼包失败，重置为空列表: " + current);
                            purchasedGifts = new HashMap<>();
                        }
                    }
                    // 添加或更新礼包购买时间
                    purchasedGifts.put(giftId, System.currentTimeMillis());
                    
                    // 转换回字符串格式
                    List<String> entries = new ArrayList<>();
                    for (Map.Entry<String, Long> entry : purchasedGifts.entrySet()) {
                        entries.add(entry.getKey() + ":" + entry.getValue());
                    }
                    String newValue = "[\"" + String.join("\",\"", entries) + "\"]";
                    
                    String updateSql = "UPDATE " + tablePrefix + "players SET purchased_gifts = ? WHERE uuid = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, newValue);
                        updateStmt.setString(2, uuid.toString());
                        updateStmt.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("添加已购买礼包失败: " + e.getMessage());
                plugin.getLogger().severe("SQL语句: " + sql);
                plugin.getLogger().severe("玩家UUID: " + uuid);
                e.printStackTrace();
            }
        });
    }

    public CompletableFuture<Set<String>> getPurchasedGifts(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> purchasedGifts = new HashSet<>();
            String sql = "SELECT purchased_gifts FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String current = rs.getString("purchased_gifts");
                        if (current != null && !current.trim().isEmpty() && !current.equals("[]")) {
                            try {
                                String[] gifts = current.replace("[", "").replace("]", "").replace("\"", "").split(",");
                                for (String gift : gifts) {
                                    gift = gift.trim();
                                    if (!gift.isEmpty()) {
                                        String[] parts = gift.split(":");
                                        if (parts.length >= 1) {
                                            purchasedGifts.add(parts[0]);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("解析已购买礼包失败，返回空列表: " + current);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取已购买礼包失败: " + e.getMessage());
            }
            return purchasedGifts;
        });
    }
    
    public CompletableFuture<Long> getGiftPurchaseTimeById(UUID uuid, String giftId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT purchased_gifts FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String current = rs.getString("purchased_gifts");
                        if (current != null && !current.trim().isEmpty() && !current.equals("[]")) {
                            try {
                                String[] gifts = current.replace("[", "").replace("]", "").replace("\"", "").split(",");
                                for (String gift : gifts) {
                                    gift = gift.trim();
                                    if (!gift.isEmpty()) {
                                        String[] parts = gift.split(":");
                                        if (parts.length >= 2 && parts[0].equals(giftId)) {
                                            return Long.parseLong(parts[1]);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("解析已购买礼包失败: " + current);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取礼包购买时间失败: " + e.getMessage());
            }
            return 0L;
        });
    }

    public CompletableFuture<Set<String>> getClaimedPermissionGroups(UUID inviterUuid, UUID inviteeUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> groups = new HashSet<>();
            String sql = "SELECT claimed_permission_groups FROM " + tablePrefix + "records WHERE inviter_uuid = ? AND invitee_uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, inviterUuid.toString());
                stmt.setString(2, inviteeUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String current = rs.getString("claimed_permission_groups");
                        if (current != null && !current.trim().isEmpty() && !current.equals("[]")) {
                            try {
                                String[] parts = current.replace("[", "").replace("]", "").replace("\"", "").split(",");
                                for (String part : parts) {
                                    if (!part.trim().isEmpty()) {
                                        groups.add(part.trim());
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("解析已领取权限组失败，返回空列表: " + current);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取已领取权限组失败: " + e.getMessage());
            }
            return groups;
        });
    }

    public CompletableFuture<Void> addClaimedPermissionGroup(UUID inviterUuid, UUID inviteeUuid, String group) {
        return CompletableFuture.runAsync(() -> {
            String sql = "SELECT claimed_permission_groups FROM " + tablePrefix + "records WHERE inviter_uuid = ? AND invitee_uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, inviterUuid.toString());
                stmt.setString(2, inviteeUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    String current = null;
                    if (rs.next()) {
                        current = rs.getString("claimed_permission_groups");
                    }
                    if (current == null || current.trim().isEmpty()) {
                        current = "[]";
                    }
                    Set<String> groups = new HashSet<>();
                    if (!current.equals("[]")) {
                        try {
                            String[] parts = current.replace("[", "").replace("]", "").replace("\"", "").split(",");
                            for (String part : parts) {
                                if (!part.trim().isEmpty()) {
                                    groups.add(part.trim());
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("解析已领取权限组失败，重置为空列表: " + current);
                            groups = new HashSet<>();
                        }
                    }
                    groups.add(group);
                    String newValue = "[\"" + String.join("\",\"", groups) + "\"]";
                    String updateSql = "UPDATE " + tablePrefix + "records SET claimed_permission_groups = ? WHERE inviter_uuid = ? AND invitee_uuid = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, newValue);
                        updateStmt.setString(2, inviterUuid.toString());
                        updateStmt.setString(3, inviteeUuid.toString());
                        updateStmt.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("添加已领取权限组失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public CompletableFuture<Void> updateMoney(UUID uuid, double amount) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET money = money + ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setDouble(1, amount);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新点券失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> updateContributionAmount(UUID uuid, double amount) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET contribution_amount = contribution_amount + ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setDouble(1, amount);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新贡献值失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Boolean> updateContributionAmount(UUID uuid, double amount, boolean returnResult) {
        return addContributionAmount(uuid, amount);
    }

    public CompletableFuture<Boolean> addContributionAmount(UUID uuid, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "UPDATE " + tablePrefix + "players SET contribution_amount = contribution_amount + ? WHERE uuid = ?";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setDouble(1, amount);
                    stmt.setString(2, uuid.toString());
                    stmt.executeUpdate();
                }
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("增加贡献值失败: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> setContributionAmount(UUID uuid, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = "UPDATE " + tablePrefix + "players SET contribution_amount = ? WHERE uuid = ?";
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setDouble(1, amount);
                    stmt.setString(2, uuid.toString());
                    stmt.executeUpdate();
                }
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("设置贡献值失败: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> deductContributionAmount(UUID uuid, double amount) {
        return addContributionAmount(uuid, -amount);
    }

    public CompletableFuture<Boolean> clearContributionAmount(UUID uuid) {
        return setContributionAmount(uuid, 0);
    }

    public CompletableFuture<Double> getContributionAmount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT contribution_amount FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("contribution_amount");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取贡献值失败: " + e.getMessage());
            }
            return 0.0;
        });
    }

    public CompletableFuture<Void> updateTotalRebatePoints(UUID uuid, double amount) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET total_rebate_points = total_rebate_points + ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setDouble(1, amount);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新累计返点失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Double> getTotalRebateAmount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT total_rebate_points FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("total_rebate_points");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取累计返点失败: " + e.getMessage());
            }
            return 0.0;
        });
    }

    public CompletableFuture<Boolean> checkCrossServerDuplicate(String transactionKey) {
        return isPointsRebateRecordProcessed(transactionKey);
    }

    public CompletableFuture<Boolean> checkRebateDuplicate(UUID uuid, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            return false; // 默认返回 false，不重复
        });
    }

    public CompletableFuture<Double> getTodayRebateTotal(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            return 0.0; // 暂时返回 0.0
        });
    }

    public CompletableFuture<Void> updateLastPermissionGroup(UUID uuid, String group) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET last_permission_group = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, group);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新上次权限组失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> updateFunctionPermissions(UUID uuid, String ip, boolean milestoneEnabled, boolean rebateEnabled, boolean giftEnabled) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET bind_ip = ?, milestone_enabled = ?, rebate_enabled = ?, gift_enabled = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, ip);
                stmt.setBoolean(2, milestoneEnabled);
                stmt.setBoolean(3, rebateEnabled);
                stmt.setBoolean(4, giftEnabled);
                stmt.setString(5, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新功能权限失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> updateFunctionPermissions(UUID uuid, boolean milestoneEnabled, boolean rebateEnabled, boolean giftEnabled) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET milestone_enabled = ?, rebate_enabled = ?, gift_enabled = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, milestoneEnabled);
                stmt.setBoolean(2, rebateEnabled);
                stmt.setBoolean(3, giftEnabled);
                stmt.setString(4, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新功能权限失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> updateBindIp(UUID uuid, String ip) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET bind_ip = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, ip);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新绑定IP失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<String> getBindIp(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT bind_ip FROM " + tablePrefix + "players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("bind_ip");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取绑定IP失败: " + e.getMessage());
            }
            return null;
        });
    }

    public CompletableFuture<Void> resetPlayerData(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = getConnection()) {
                // 1. 先获取被邀请者的邀请人，后续需要回滚邀请计数
                UUID inviterUuid = null;
                String getInviterSql = "SELECT inviter_uuid FROM " + tablePrefix + "records WHERE invitee_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(getInviterSql)) {
                    stmt.setString(1, uuid.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String inviterStr = rs.getString("inviter_uuid");
                            if (inviterStr != null) {
                                inviterUuid = UUID.fromString(inviterStr);
                            }
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().warning("获取邀请人失败: " + e.getMessage());
                }
                
                // 2. 回滚邀请人的邀请计数
                if (inviterUuid != null) {
                    String updateInviterSql = "UPDATE " + tablePrefix + "players SET total_invites = total_invites - 1 WHERE uuid = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(updateInviterSql)) {
                        stmt.setString(1, inviterUuid.toString());
                        stmt.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("回滚邀请人计数失败: " + e.getMessage());
                    }
                }
                
                // 3. 删除邀请记录
                String deleteRecordsSql = "DELETE FROM " + tablePrefix + "records WHERE invitee_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(deleteRecordsSql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.executeUpdate();
                }
                
                // 4. 删除待领取里程碑
                String deletePendingSql = "DELETE FROM " + tablePrefix + "pending_milestones WHERE player_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(deletePendingSql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.executeUpdate();
                }
                
                // 5. 重置玩家主表数据（保留邀请码）
                String resetPlayerSql = "UPDATE " + tablePrefix + "players SET " +
                    "total_invites = 0, " +
                    "claimed_milestones = '[]', " +
                    "announced_milestones = '[]', " +
                    "gift_id = NULL, " +
                    "purchased_gifts = '[]', " +
                    "gift_purchase_time = NULL, " +
                    "money = 0, " +
                    "contribution_amount = 0, " +
                    "total_rebate_amount = 0, " +
                    "total_rebate_points = 0, " +
                    "bind_ip = NULL, " +
                    "milestone_enabled = 1, " +
                    "rebate_enabled = 1, " +
                    "gift_enabled = 1, " +
                    "last_permission_group = NULL " +
                    "WHERE uuid = ?";
                
                try (PreparedStatement stmt = conn.prepareStatement(resetPlayerSql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.executeUpdate();
                }
                
                plugin.getLogger().info("玩家数据已重置: " + uuid);
            } catch (SQLException e) {
                plugin.getLogger().severe("重置玩家数据失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public CompletableFuture<Void> addPointsRebateRecord(String transactionKey, UUID playerUuid, double amount, UUID inviterUuid, double rebateAmount) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO " + tablePrefix + "points_rebate (transaction_key, player_uuid, amount, inviter_uuid, rebate_amount, created_at, status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, transactionKey);
                stmt.setString(2, playerUuid.toString());
                stmt.setDouble(3, amount);
                stmt.setString(4, inviterUuid != null ? inviterUuid.toString() : null);
                stmt.setDouble(5, rebateAmount);
                stmt.setLong(6, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("添加点券返点记录失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Boolean> isPointsRebateRecordProcessed(String transactionKey) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT status FROM " + tablePrefix + "points_rebate WHERE transaction_key = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, transactionKey);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String status = rs.getString("status");
                        return "PROCESSED".equals(status);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("检查点券返点记录是否已处理失败: " + e.getMessage());
            }
            return false;
        });
    }

    public CompletableFuture<Void> markPointsRebateRecordProcessed(String transactionKey) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "points_rebate SET status = 'PROCESSED', processed_at = ? WHERE transaction_key = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, transactionKey);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("标记点券返点记录为已处理失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> addAnnouncement(String serverId, String message) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO " + tablePrefix + "announcements (server_id, message, created_at) VALUES (?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, serverId);
                stmt.setString(2, message);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("添加公告失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<List<String>> getUnbroadcastedAnnouncements(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> announcements = new ArrayList<>();
            String sql = "SELECT message FROM " + tablePrefix + "announcements WHERE broadcasted = 0 AND (server_id = 'all' OR server_id = ?) ORDER BY created_at ASC";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, serverId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        announcements.add(rs.getString("message"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取未广播公告失败: " + e.getMessage());
            }
            return announcements;
        });
    }

    public CompletableFuture<Void> markAnnouncementBroadcasted(String serverId, String message) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE " + tablePrefix + "announcements SET broadcasted = 1, broadcasted_at = ? WHERE message = ? AND (server_id = 'all' OR server_id = ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, message);
                stmt.setString(3, serverId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("标记公告为已广播失败: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<List<PlayerData>> getTopPlayersByInvites(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerData> players = new ArrayList<>();
            String sql = "SELECT * FROM " + tablePrefix + "players ORDER BY total_invites DESC LIMIT ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        PlayerData data = new PlayerData();
                        data.uuid = UUID.fromString(rs.getString("uuid"));
                        data.inviteCode = rs.getString("invite_code");
                        data.totalInvites = rs.getInt("total_invites");
                        data.claimedMilestones = rs.getString("claimed_milestones");
                        data.announcedMilestones = rs.getString("announced_milestones");
                        data.giftId = rs.getString("gift_id");
                        data.purchasedGifts = rs.getString("purchased_gifts");
                        data.money = rs.getDouble("money");
                        data.lastInviteTime = rs.getLong("last_invite_time");
                        data.lastCodeChange = rs.getLong("last_code_change");
                        data.lastPermissionGroup = rs.getString("last_permission_group");
                        data.createdAt = rs.getLong("created_at");
                        data.milestoneEnabled = rs.getBoolean("milestone_enabled");
                        data.rebateEnabled = rs.getBoolean("rebate_enabled");
                        data.giftEnabled = rs.getBoolean("gift_enabled");
                        data.contributionAmount = rs.getDouble("contribution_amount");
                        data.totalRebatePoints = rs.getDouble("total_rebate_points");
                        data.giftPurchaseTime = rs.getLong("gift_purchase_time");
                        players.add(data);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取排行榜玩家失败: " + e.getMessage());
            }
            return players;
        });
    }

    public CompletableFuture<List<PlayerData>> getTopPlayersByContribution(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerData> players = new ArrayList<>();
            String sql = "SELECT * FROM " + tablePrefix + "players ORDER BY contribution_amount DESC LIMIT ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        PlayerData data = new PlayerData();
                        data.uuid = UUID.fromString(rs.getString("uuid"));
                        data.inviteCode = rs.getString("invite_code");
                        data.totalInvites = rs.getInt("total_invites");
                        data.claimedMilestones = rs.getString("claimed_milestones");
                        data.announcedMilestones = rs.getString("announced_milestones");
                        data.giftId = rs.getString("gift_id");
                        data.purchasedGifts = rs.getString("purchased_gifts");
                        data.money = rs.getDouble("money");
                        data.lastInviteTime = rs.getLong("last_invite_time");
                        data.lastCodeChange = rs.getLong("last_code_change");
                        data.lastPermissionGroup = rs.getString("last_permission_group");
                        data.createdAt = rs.getLong("created_at");
                        data.milestoneEnabled = rs.getBoolean("milestone_enabled");
                        data.rebateEnabled = rs.getBoolean("rebate_enabled");
                        data.giftEnabled = rs.getBoolean("gift_enabled");
                        data.contributionAmount = rs.getDouble("contribution_amount");
                        data.totalRebatePoints = rs.getDouble("total_rebate_points");
                        data.giftPurchaseTime = rs.getLong("gift_purchase_time");
                        players.add(data);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取贡献榜玩家失败: " + e.getMessage());
            }
            return players;
        });
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public static class PlayerData {
        public UUID uuid;
        public String inviteCode;
        public int totalInvites;
        public String claimedMilestones;
        public String announcedMilestones;
        public String giftId;
        public String purchasedGifts;
        public double money;
        public double contributionAmount;
        public double totalRebatePoints;
        public long lastInviteTime;
        public long lastCodeChange;
        public String lastPermissionGroup;
        public long createdAt;
        public boolean milestoneEnabled;
        public boolean rebateEnabled;
        public boolean giftEnabled;
        public long giftPurchaseTime;
    }
}
