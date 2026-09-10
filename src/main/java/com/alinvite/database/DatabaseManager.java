package com.alinvite.database;

import com.alinvite.ALInvite;
import com.alinvite.utils.AsyncPool;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库访问层（HikariCP + SQLite/MySQL）。
 *
 * 线程模型：所有查询的同步实现在 *Sync 方法里（调用方必须已在 IO 线程，如 AsyncPool / 缓存加载器），
 * 公开的 CompletableFuture 方法统一经 AsyncPool 提交，杜绝 ForkJoinPool.commonPool 与嵌套 join。
 * 伪 JSON 列的读改写统一按 key 加锁，防止并发写丢更新。
 */
public class DatabaseManager {

    private final ALInvite plugin;
    private String tablePrefix;
    private HikariDataSource dataSource;

    /**
     * 数据变更监听：写方法执行后回调（本机缓存立即失效 + 跨服广播），
     * 保证集群内其它服务器零延迟感知数据变化。回调运行在 IO 线程。
     */
    public interface DataChangeListener {
        void onDataChanged(UUID playerId, String kind);
    }

    private volatile DataChangeListener dataChangeListener;

    public void setDataChangeListener(DataChangeListener listener) {
        this.dataChangeListener = listener;
    }

    private void notifyChange(UUID playerId, String kind) {
        DataChangeListener listener = dataChangeListener;
        if (listener != null) {
            try {
                listener.onDataChanged(playerId, kind);
            } catch (Exception e) {
                plugin.getLogger().warning("数据变更通知失败: " + e.getMessage());
            }
        }
    }
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final Object poolInitLock = new Object();

    /** 伪 JSON 列读改写锁：key = 列前缀 + uuid。 */
    private final ConcurrentHashMap<String, Object> rowLocks = new ConcurrentHashMap<>();

    private Object lockFor(String key) {
        return rowLocks.computeIfAbsent(key, ignored -> new Object());
    }

    public DatabaseManager(ALInvite plugin) {
        this.plugin = plugin;
    }

    public void init() {
        String type = plugin.getConfigManager().getDatabaseConfig().getString("database.type", "sqlite");
        this.tablePrefix = plugin.getConfigManager().getDatabaseConfig().getString("database.table_prefix", "alinvite_");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("ALInvite-Pool");

        if (type.equalsIgnoreCase("mysql")) {
            String host = plugin.getConfigManager().getDatabaseConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfigManager().getDatabaseConfig().getInt("database.mysql.port", 3306);
            String database = plugin.getConfigManager().getDatabaseConfig().getString("database.mysql.database", "minecraft");
            String user = plugin.getConfigManager().getDatabaseConfig().getString("database.mysql.user", "root");
            String password = plugin.getConfigManager().getDatabaseConfig().getString("database.mysql.password", "");
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(password);
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setConnectionTimeout(30000);
        } else {
            String dbFile = plugin.getConfigManager().getDatabaseConfig().getString("database.sqlite_file", "data.db");
            File file = new File(plugin.getDataFolder(), dbFile);
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikariConfig.setMaximumPoolSize(5);
            hikariConfig.setConnectionTimeout(5000);
            hikariConfig.setMinimumIdle(1);
        }

        this.dataSource = new HikariDataSource(hikariConfig);

        createTables();
    }

    public Connection getConnection() throws SQLException {
        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            boolean autoReconnect = plugin.getConfigManager().getDatabaseConfig().getBoolean("database.auto_reconnect", true);
            if (!autoReconnect) {
                throw new SQLException("数据库连接池已关闭，且未启用自动重连");
            }
            int maxAttempts = plugin.getConfigManager().getDatabaseConfig().getInt("database.max_reconnect_attempts", 3);
            if (maxAttempts > 0 && reconnectAttempts.get() >= maxAttempts) {
                throw new SQLException("数据库连接池重连次数已达到上限 (" + maxAttempts + "次)");
            }
            synchronized (poolInitLock) {
                if (dataSource == null || dataSource.isClosed()) {
                    reconnectAttempts.incrementAndGet();
                    plugin.getLogger().warning("数据库连接池已关闭，尝试重新初始化 (第 " + reconnectAttempts.get() + " 次)...");
                    closePool();
                    init();
                    reconnectAttempts.set(0);
                }
            }
        }
        return dataSource.getConnection();
    }

    private void closePool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void close() {
        closePool();
    }

    // ───────────────────────── 建表与结构 ─────────────────────────

    private void createTables() {
        String type = plugin.getConfigManager().getDatabaseConfig().getString("database.type", "sqlite");
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
                `unclaimed_rebate` REAL NOT NULL DEFAULT 0,
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

        executeUpdate(playersTable.replace("{prefix}", tablePrefix));
        executeUpdate(recordsTable.replace("{prefix}", tablePrefix));
        executeUpdate(announcementsTable.replace("{prefix}", tablePrefix));
        executeUpdate(pendingMilestonesTable.replace("{prefix}", tablePrefix));
        executeUpdate(pointsRebateTable.replace("{prefix}", tablePrefix));

        String rebateRecordsTable = """
            CREATE TABLE IF NOT EXISTS `{prefix}rebate_records` (
                `id` INTEGER PRIMARY KEY {autoIncrement},
                `player_uuid` VARCHAR(36) NOT NULL,
                `amount` DECIMAL(10,2) NOT NULL,
                `source_name` VARCHAR(16),
                `type` VARCHAR(16) NOT NULL DEFAULT 'rebate',
                `created_at` BIGINT NOT NULL
            )
            """.replace("{autoIncrement}", autoIncrementSyntax);

        executeUpdate(rebateRecordsTable.replace("{prefix}", tablePrefix));

        createUniqueInviteeIndex();

        updateTableStructure();

        createIndexes();
    }

    /**
     * records.invitee_uuid 唯一索引：跨服/并发环境下防止同一玩家邀请被重复计数。
     * 幂等创建；若历史数据存在重复导致创建失败，仅警告（插件继续以非唯一索引运行）。
     */
    private void createUniqueInviteeIndex() {
        String indexName = "idx_" + tablePrefix + "records_invitee_uniq";
        String sql = "CREATE UNIQUE INDEX IF NOT EXISTS " + indexName + " ON " + tablePrefix + "records(invitee_uuid)";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            // MySQL 不支持 IF NOT EXISTS：索引已存在时会报错，属正常
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("duplicate")) {
                return;
            }
            plugin.getLogger().warning("创建邀请记录唯一索引失败（跨服防重复计数受限，可能存在历史重复数据）: " + e.getMessage());
        }
    }

    private void createIndexes() {
        plugin.getLogger().info("正在创建数据库索引...");

        String type = plugin.getConfigManager().getDatabaseConfig().getString("database.type", "sqlite");
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
        indexes.put("idx_" + tablePrefix + "rebate_records_player", new String[]{tablePrefix + "rebate_records", "player_uuid", "created_at"});

        for (Map.Entry<String, String[]> entry : indexes.entrySet()) {
            String indexName = entry.getKey();
            String tableName = entry.getValue()[0];
            String columnName = entry.getValue()[1];

            if (isMySQL) {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SHOW INDEX FROM " + tableName + " WHERE Key_name = ?")) {
                    ps.setString(1, indexName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            executeUpdate("CREATE INDEX " + indexName + " ON " + tableName + "(" + columnName + ")");
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().warning("创建索引 " + indexName + " 时出错: " + e.getMessage());
                }
            } else {
                executeUpdate("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + "(" + columnName + ")");
            }
        }

        plugin.getLogger().info("数据库索引创建完成！");
    }

    private void updateTableStructure() {
        String type = plugin.getConfigManager().getDatabaseConfig().getString("database.type", "sqlite");
        boolean isMySQL = type.equalsIgnoreCase("mysql");

        try {
            if (!checkColumnExists("players", "contribution_amount", isMySQL)) {
                String alterTableSql = isMySQL
                        ? "ALTER TABLE " + tablePrefix + "players ADD COLUMN contribution_amount DECIMAL(10,2) NOT NULL DEFAULT 0"
                        : "ALTER TABLE " + tablePrefix + "players ADD COLUMN contribution_amount REAL NOT NULL DEFAULT 0";
                executeUpdate(alterTableSql);
                plugin.getLogger().info("已为数据库表添加 contribution_amount 字段");
            }

            addColumnIfMissing("players", "milestone_enabled", isMySQL,
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN milestone_enabled BOOLEAN NOT NULL DEFAULT 1",
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN milestone_enabled TINYINT(1) NOT NULL DEFAULT 1");
            addColumnIfMissing("players", "rebate_enabled", isMySQL,
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN rebate_enabled BOOLEAN NOT NULL DEFAULT 1",
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN rebate_enabled TINYINT(1) NOT NULL DEFAULT 1");
            addColumnIfMissing("players", "gift_enabled", isMySQL,
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN gift_enabled BOOLEAN NOT NULL DEFAULT 1",
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN gift_enabled TINYINT(1) NOT NULL DEFAULT 1");
            addColumnIfMissing("players", "bind_ip", isMySQL,
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN bind_ip VARCHAR(45)",
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN bind_ip VARCHAR(45)");
            addColumnIfMissing("players", "gift_purchase_time", isMySQL,
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN gift_purchase_time BIGINT",
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN gift_purchase_time BIGINT");
            addColumnIfMissing("players", "total_rebate_amount", isMySQL,
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN total_rebate_amount REAL NOT NULL DEFAULT 0",
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN total_rebate_amount DECIMAL(10,2) NOT NULL DEFAULT 0");
            addColumnIfMissing("players", "unclaimed_rebate", isMySQL,
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN unclaimed_rebate REAL NOT NULL DEFAULT 0",
                    "ALTER TABLE " + tablePrefix + "players ADD COLUMN unclaimed_rebate DECIMAL(10,2) NOT NULL DEFAULT 0");
            // rebate_records 的 type 列（兼容旧表）
            if (!checkColumnExists("rebate_records", "type", isMySQL)) {
                executeUpdate("ALTER TABLE " + tablePrefix + "rebate_records ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'rebate'");
                plugin.getLogger().info("已为 rebate_records 表添加 type 字段");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("更新数据库表结构失败: " + e.getMessage());
        }
    }

    private void addColumnIfMissing(String table, String column, boolean isMySQL, String sqliteSql, String mysqlSql) throws SQLException {
        if (!checkColumnExists(table, column, isMySQL)) {
            executeUpdate(isMySQL ? mysqlSql : sqliteSql);
            plugin.getLogger().info("已为数据库表添加 " + column + " 字段");
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
                            if (columnName.equals(rs.getString("name"))) {
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
            return -1;
        }
    }

    // ───────────────────────── 玩家主表 ─────────────────────────

    public PlayerData getPlayerDataSync(UUID uuid) {
        String sql = "SELECT * FROM " + tablePrefix + "players WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapPlayerRow(uuid, rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("获取玩家数据失败: " + e.getMessage() + " (uuid=" + uuid + ")");
        }
        return null;
    }

    public CompletableFuture<PlayerData> getPlayerData(UUID uuid) {
        return AsyncPool.supply(() -> getPlayerDataSync(uuid));
    }

    public CompletableFuture<Void> createPlayerData(UUID uuid, String inviteCode) {
        return AsyncPool.run(() -> createPlayerDataSync(uuid, inviteCode));
    }

    public void createPlayerDataSync(UUID uuid, String inviteCode) {
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
    }

    public CompletableFuture<String> getInviteCodeByPlayer(UUID uuid) {
        return AsyncPool.supply(() -> getInviteCodeByPlayerSync(uuid));
    }

    public String getInviteCodeByPlayerSync(UUID uuid) {
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
    }

    public String getPlayerByInviteCodeSync(String code) {
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
    }

    public CompletableFuture<String> getPlayerByInviteCode(String code) {
        return AsyncPool.supply(() -> getPlayerByInviteCodeSync(code));
    }

    /** 同线程直查，消除嵌套 join。 */
    public CompletableFuture<UUID> getInviterUUIDByInviteCode(String code) {
        return AsyncPool.supply(() -> {
            String uuid = getPlayerByInviteCodeSync(code);
            return uuid != null ? UUID.fromString(uuid) : null;
        });
    }

    public CompletableFuture<Boolean> isInviteCodeExists(String code) {
        return AsyncPool.supply(() -> getPlayerByInviteCodeSync(code) != null);
    }

    public CompletableFuture<UUID> getInviterByInvitee(UUID inviteeUuid) {
        return AsyncPool.supply(() -> getInviterSync(inviteeUuid));
    }

    public UUID getInviterSync(UUID inviteeUuid) {
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
    }

    public CompletableFuture<UUID> getInviter(UUID inviteeUuid) {
        return getInviterByInvitee(inviteeUuid);
    }

    public CompletableFuture<Boolean> hasUsedInviteCode(UUID uuid) {
        return AsyncPool.supply(() -> hasUsedInviteCodeSync(uuid));
    }

    public boolean hasUsedInviteCodeSync(UUID uuid) {
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
    }

    public int getIpInviteCountSync(String ip) {
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
    }

    public CompletableFuture<Integer> getIpInviteCount(String ip) {
        return AsyncPool.supply(() -> getIpInviteCountSync(ip));
    }

    public CompletableFuture<Void> recordInvite(UUID inviterUuid, UUID inviteeUuid, String inviteeIp, String inviteeName) {
        return addInviteRecord(inviterUuid, inviteeUuid, inviteeIp, inviteeName).thenApply(inserted -> null);
    }

    /**
     * 记录邀请。利用 invitee_uuid 唯一索引做数据库级防重：
     * 同一玩家只能被记录一次，跨服并发插入也只有一次生效。
     *
     * @return true = 本次插入成功（应继续计数/发奖）；false = 该玩家已有邀请记录（忽略，防止二次发放）
     */
    public CompletableFuture<Boolean> addInviteRecord(UUID inviterUuid, UUID inviteeUuid, String inviteeIp, String inviteeName) {
        return AsyncPool.supply(() -> addInviteRecordSync(inviterUuid, inviteeUuid, inviteeIp, inviteeName));
    }

    public boolean addInviteRecordSync(UUID inviterUuid, UUID inviteeUuid, String inviteeIp, String inviteeName) {
        boolean isMySQL = "mysql".equalsIgnoreCase(plugin.getConfigManager().getDatabaseConfig().getString("database.type", "sqlite"));
        String sql = (isMySQL ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ")
            + tablePrefix + "records (inviter_uuid, invitee_uuid, invitee_ip, invitee_name, invited_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, inviterUuid.toString());
            stmt.setString(2, inviteeUuid.toString());
            stmt.setString(3, inviteeIp);
            stmt.setString(4, inviteeName);
            stmt.setLong(5, System.currentTimeMillis());
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().severe("记录邀请失败: " + e.getMessage());
            return false;
        }
    }

    public CompletableFuture<Void> updateTotalInvites(UUID uuid, int total) {
        return updateInviteCount(uuid, total);
    }

    public CompletableFuture<Void> updateInviteCount(UUID uuid, int total) {
        return AsyncPool.run(() -> {
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
            notifyChange(uuid, "stats");
        });
    }

    public CompletableFuture<Void> updatePermissionGroup(UUID uuid, String group) {
        return updateLastPermissionGroup(uuid, group);
    }

    // ───────────────────────── 里程碑（伪 JSON 列，读改写加锁） ─────────────────────────

    public String getClaimedMilestonesSync(UUID uuid) {
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
    }

    public CompletableFuture<String> getClaimedMilestones(UUID uuid) {
        return AsyncPool.supply(() -> getClaimedMilestonesSync(uuid));
    }

    public CompletableFuture<Boolean> claimMilestone(UUID uuid, String milestoneId) {
        return AsyncPool.supply(() -> claimMilestoneSync(uuid, milestoneId));
    }

    /**
     * 原子领取里程碑：数据库层条件更新（仅当记录中尚无该里程碑时写入），
     * 跨服并发领取只有一方能成功，从根源防止奖励二次发放。
     *
     * @return true = 抢占成功（应发放奖励）；false = 已被领取过（本机或其它服务器抢先）
     */
    public boolean claimMilestoneSync(UUID uuid, String milestoneId) {
        synchronized (lockFor("claimed:" + uuid)) {
            Set<String> claimedSet = parseStringSet(getClaimedMilestonesSync(uuid));
            if (!claimedSet.add(milestoneId)) {
                return false;
            }
            String newValue = "[\"" + String.join("\",\"", claimedSet) + "\"]";
            String sql = "UPDATE " + tablePrefix + "players SET claimed_milestones = ? "
                + "WHERE uuid = ? AND (claimed_milestones IS NULL OR claimed_milestones NOT LIKE ?)";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newValue);
                stmt.setString(2, uuid.toString());
                stmt.setString(3, "%\"" + milestoneId + "\"%");
                return stmt.executeUpdate() == 1;
            } catch (SQLException e) {
                plugin.getLogger().severe("领取里程碑写入失败: " + e.getMessage());
                return false;
            }
        }
    }

    public String getAnnouncedMilestonesSync(UUID uuid) {
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
    }

    public CompletableFuture<String> getAnnouncedMilestones(UUID uuid) {
        return AsyncPool.supply(() -> getAnnouncedMilestonesSync(uuid));
    }

    public CompletableFuture<Void> addAnnouncedMilestone(UUID uuid, String milestoneId) {
        return AsyncPool.run(() -> addAnnouncedMilestoneSync(uuid, milestoneId));
    }

    public void addAnnouncedMilestoneSync(UUID uuid, String milestoneId) {
        synchronized (lockFor("announced:" + uuid)) {
            Set<String> announcedSet = parseStringSet(getAnnouncedMilestonesSync(uuid));
            announcedSet.add(milestoneId);
            writeStringSet("announced_milestones", announcedSet, uuid);
        }
    }

    private void writeStringSet(String column, Set<String> values, UUID uuid) {
        String newValue = "[\"" + String.join("\",\"", values) + "\"]";
        String updateSql = "UPDATE " + tablePrefix + "players SET " + column + " = ? WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setString(1, newValue);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("写入 " + column + " 失败: " + e.getMessage());
        }
    }

    private Set<String> parseStringSet(String json) {
        Set<String> result = new HashSet<>();
        if (json == null || json.trim().isEmpty() || json.equals("[]")) {
            return result;
        }
        try {
            String[] parts = json.replace("[", "").replace("]", "").replace("\"", "").split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("解析伪 JSON 列失败，按空处理: " + json);
        }
        return result;
    }

    public CompletableFuture<Void> addPendingMilestone(UUID uuid, String milestoneKey) {
        return AsyncPool.run(() -> {
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
        return AsyncPool.supply(() -> {
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
        return AsyncPool.run(() -> {
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

    // ───────────────────────── 礼包 ─────────────────────────

    public String getGiftIdSync(UUID uuid) {
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
    }

    public CompletableFuture<String> getGiftId(UUID uuid) {
        return AsyncPool.supply(() -> getGiftIdSync(uuid));
    }

    public long getGiftPurchaseTimeSync(UUID uuid) {
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
    }

    public CompletableFuture<Long> getGiftPurchaseTime(UUID uuid) {
        return AsyncPool.supply(() -> getGiftPurchaseTimeSync(uuid));
    }

    public CompletableFuture<Void> setGiftId(UUID uuid, String giftId) {
        return AsyncPool.run(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET gift_id = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, giftId);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("设置礼包ID失败: " + e.getMessage());
            }
            notifyChange(uuid, "gift");
        });
    }

    public CompletableFuture<Void> setGiftPurchaseTime(UUID uuid, long purchaseTime) {
        return AsyncPool.run(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET gift_purchase_time = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, purchaseTime);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("设置礼包购买时间失败: " + e.getMessage());
            }
            notifyChange(uuid, "gift");
        });
    }

    public CompletableFuture<Void> updateInviteCode(UUID uuid, String newCode) {
        return AsyncPool.run(() -> {
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
            notifyChange(uuid, "invitecode");
        });
    }

    public CompletableFuture<Void> clearInviteCode(UUID uuid) {
        return AsyncPool.run(() -> {
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
            notifyChange(uuid, "invitecode");
        });
    }

    public Map<String, Long> getPurchasedGiftsWithTimeSync(UUID uuid) {
        Map<String, Long> purchasedGifts = new HashMap<>();
        String raw = queryPlayersColumn(uuid, "purchased_gifts");
        if (raw == null || raw.trim().isEmpty() || raw.equals("[]")) {
            return purchasedGifts;
        }
        try {
            String[] gifts = raw.replace("[", "").replace("]", "").replace("\"", "").split(",");
            for (String gift : gifts) {
                String trimmed = gift.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split(":");
                if (parts.length >= 2) {
                    try {
                        purchasedGifts.put(parts[0], Long.parseLong(parts[1]));
                    } catch (NumberFormatException ignored) {
                        purchasedGifts.put(parts[0], 0L);
                    }
                } else {
                    purchasedGifts.put(parts[0], 0L);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("解析已购买礼包失败，按空处理: " + raw);
        }
        return purchasedGifts;
    }

    private String queryPlayersColumn(UUID uuid, String column) {
        String sql = "SELECT " + column + " FROM " + tablePrefix + "players WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(column);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("读取 " + column + " 失败: " + e.getMessage());
        }
        return null;
    }

    /** 读改写加锁，防止并发写丢更新。 */
    public CompletableFuture<Void> addPurchasedGift(UUID uuid, String giftId) {
        return AsyncPool.run(() -> addPurchasedGiftSync(uuid, giftId));
    }

    public void addPurchasedGiftSync(UUID uuid, String giftId) {
        synchronized (lockFor("purchased:" + uuid)) {
            Map<String, Long> purchased = getPurchasedGiftsWithTimeSync(uuid);
            purchased.put(giftId, System.currentTimeMillis());
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, Long> entry : purchased.entrySet()) {
                entries.add(entry.getKey() + ":" + entry.getValue());
            }
            String newValue = "[\"" + String.join("\",\"", entries) + "\"]";
            String updateSql = "UPDATE " + tablePrefix + "players SET purchased_gifts = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, newValue);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("添加已购买礼包失败: " + e.getMessage());
            }
            notifyChange(uuid, "gift");
        }
    }

    public Set<String> getPurchasedGiftsSync(UUID uuid) {
        return new HashSet<>(getPurchasedGiftsWithTimeSync(uuid).keySet());
    }

    public CompletableFuture<Set<String>> getPurchasedGifts(UUID uuid) {
        return AsyncPool.supply(() -> getPurchasedGiftsSync(uuid));
    }

    public long getGiftPurchaseTimeByIdSync(UUID uuid, String giftId) {
        Long time = getPurchasedGiftsWithTimeSync(uuid).get(giftId);
        return time == null ? 0L : time;
    }

    public CompletableFuture<Long> getGiftPurchaseTimeById(UUID uuid, String giftId) {
        return AsyncPool.supply(() -> getGiftPurchaseTimeByIdSync(uuid, giftId));
    }

    // ───────────────────────── 权限组记录 ─────────────────────────

    public Set<String> getClaimedPermissionGroupsSync(UUID inviterUuid, UUID inviteeUuid) {
        Set<String> groups = new HashSet<>();
        String sql = "SELECT claimed_permission_groups FROM " + tablePrefix + "records WHERE inviter_uuid = ? AND invitee_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, inviterUuid.toString());
            stmt.setString(2, inviteeUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    groups = parseStringSet(rs.getString("claimed_permission_groups"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("获取已领取权限组失败: " + e.getMessage());
        }
        return groups;
    }

    public CompletableFuture<Set<String>> getClaimedPermissionGroups(UUID inviterUuid, UUID inviteeUuid) {
        return AsyncPool.supply(() -> getClaimedPermissionGroupsSync(inviterUuid, inviteeUuid));
    }

    public CompletableFuture<Void> addClaimedPermissionGroup(UUID inviterUuid, UUID inviteeUuid, String group) {
        return AsyncPool.run(() -> addClaimedPermissionGroupSync(inviterUuid, inviteeUuid, group));
    }

    public void addClaimedPermissionGroupSync(UUID inviterUuid, UUID inviteeUuid, String group) {
        synchronized (lockFor("groups:" + inviterUuid + ":" + inviteeUuid)) {
            Set<String> groups = getClaimedPermissionGroupsSync(inviterUuid, inviteeUuid);
            groups.add(group);
            String newValue = "[\"" + String.join("\",\"", groups) + "\"]";
            String updateSql = "UPDATE " + tablePrefix + "records SET claimed_permission_groups = ? WHERE inviter_uuid = ? AND invitee_uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, newValue);
                stmt.setString(2, inviterUuid.toString());
                stmt.setString(3, inviteeUuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("添加已领取权限组失败: " + e.getMessage());
            }
        }
    }

    // ───────────────────────── 经济数值 ─────────────────────────

    public CompletableFuture<Void> updateMoney(UUID uuid, double amount) {
        return AsyncPool.run(() -> {
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
        return addContributionAmount(uuid, amount).thenApply(success -> null);
    }

    public CompletableFuture<Boolean> updateContributionAmount(UUID uuid, double amount, boolean returnResult) {
        return addContributionAmount(uuid, amount);
    }

    public CompletableFuture<Boolean> addContributionAmount(UUID uuid, double amount) {
        return AsyncPool.supply(() -> addContributionAmountSync(uuid, amount));
    }

    public boolean addContributionAmountSync(UUID uuid, double amount) {
        String sql = "UPDATE " + tablePrefix + "players SET contribution_amount = contribution_amount + ? WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, amount);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
            notifyChange(uuid, "contribution");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("增加贡献值失败: " + e.getMessage());
            return false;
        }
    }

    public CompletableFuture<Boolean> setContributionAmount(UUID uuid, double amount) {
        return AsyncPool.supply(() -> {
            String sql = "UPDATE " + tablePrefix + "players SET contribution_amount = ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setDouble(1, amount);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
                notifyChange(uuid, "contribution");
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

    public double getContributionAmountSync(UUID uuid) {
        String raw = queryPlayersColumn(uuid, "contribution_amount");
        if (raw == null || raw.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public CompletableFuture<Double> getContributionAmount(UUID uuid) {
        return AsyncPool.supply(() -> getContributionAmountSync(uuid));
    }

    public CompletableFuture<Void> updateTotalRebatePoints(UUID uuid, double amount) {
        return AsyncPool.run(() -> updateTotalRebatePointsSync(uuid, amount));
    }

    public void updateTotalRebatePointsSync(UUID uuid, double amount) {
        String sql = "UPDATE " + tablePrefix + "players SET total_rebate_points = total_rebate_points + ? WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, amount);
            stmt.setString(2, uuid.toString());
            stmt.executeUpdate();
            notifyChange(uuid, "rebate");
        } catch (SQLException e) {
            plugin.getLogger().severe("更新累计返点失败: " + e.getMessage());
        }
    }

    public double getTotalRebateAmountSync(UUID uuid) {
        String raw = queryPlayersColumn(uuid, "total_rebate_points");
        if (raw == null || raw.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public CompletableFuture<Double> getTotalRebateAmount(UUID uuid) {
        return AsyncPool.supply(() -> getTotalRebateAmountSync(uuid));
    }

    // ───────────────────────── 未领取返点池 + 返利记录 ─────────────────────────

    public double getUnclaimedRebateSync(UUID uuid) {
        String raw = queryPlayersColumn(uuid, "unclaimed_rebate");
        if (raw == null || raw.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public CompletableFuture<Double> getUnclaimedRebate(UUID uuid) {
        return AsyncPool.supply(() -> getUnclaimedRebateSync(uuid));
    }

    public void addUnclaimedRebateSync(UUID uuid, double amount) {
        synchronized (lockFor("unclaimed:" + uuid)) {
            String sql = "UPDATE " + tablePrefix + "players SET unclaimed_rebate = unclaimed_rebate + ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setDouble(1, amount);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("累加未领取返点失败: " + e.getMessage());
            }
        }
    }

    /**
     * 手动领取：原子地把未领取池清零并计入贡献返点，返回实际领取的金额（0 表示无可领取）。
     */
    public double claimUnclaimedRebateSync(UUID uuid) {
        synchronized (lockFor("unclaimed:" + uuid)) {
            double claimed = getUnclaimedRebateSync(uuid);
            if (claimed <= 0) {
                return 0;
            }
            String sql = "UPDATE " + tablePrefix + "players SET unclaimed_rebate = 0, contribution_amount = contribution_amount + ? WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setDouble(1, claimed);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
                notifyChange(uuid, "contribution");
                return claimed;
            } catch (SQLException e) {
                plugin.getLogger().severe("领取未领取返点失败: " + e.getMessage());
                return 0;
            }
        }
    }

    /**
     * 管理员核销：把未领取池清零并返回核销金额（现金模式线下发放后由管理员执行）。
     */
    public double clearUnclaimedRebateSync(UUID uuid) {
        synchronized (lockFor("unclaimed:" + uuid)) {
            double cleared = getUnclaimedRebateSync(uuid);
            if (cleared <= 0) {
                return 0;
            }
            String sql = "UPDATE " + tablePrefix + "players SET unclaimed_rebate = 0 WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
                notifyChange(uuid, "unclaimed");
                return cleared;
            } catch (SQLException e) {
                plugin.getLogger().severe("核销未领取返点失败: " + e.getMessage());
                return 0;
            }
        }
    }

    public CompletableFuture<Double> clearUnclaimedRebate(UUID uuid) {
        return AsyncPool.supply(() -> clearUnclaimedRebateSync(uuid));
    }

    public CompletableFuture<Double> claimUnclaimedRebate(UUID uuid) {
        return AsyncPool.supply(() -> claimUnclaimedRebateSync(uuid));
    }

    /** 写入一条操作记录。type: 'rebate'（入池）或 'claim'（领取）。 */
    public void addRebateRecordSync(UUID uuid, String type, double amount, String sourceName) {
        String sql = "INSERT INTO " + tablePrefix + "rebate_records (player_uuid, type, amount, source_name, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, type);
            stmt.setDouble(3, amount);
            stmt.setString(4, sourceName);
            stmt.setLong(5, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("写入返利记录失败: " + e.getMessage());
        }
    }

    /**
     * 清理超期的返利/领取记录（两种 type 一并清理，按 created_at 判断）。
     * retentionDays <= 0 表示永久保留，直接返回。返回删除条数。
     */
    public int cleanupRebateRecordsSync(int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L;
        String sql = "DELETE FROM " + tablePrefix + "rebate_records WHERE created_at < ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, cutoff);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("清理返利/领取记录失败: " + e.getMessage());
            return 0;
        }
    }

    /** 最近 limit 条记录（时间倒序）。type 可选过滤；null 返回全部。 */
    public List<RebateRecord> getRebateRecordsSync(UUID uuid, String type, int limit) {
        List<RebateRecord> records = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT type, amount, source_name, created_at FROM " + tablePrefix + "rebate_records WHERE player_uuid = ?");
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = ?");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int paramIdx = 1;
            stmt.setString(paramIdx++, uuid.toString());
            if (type != null && !type.isBlank()) {
                stmt.setString(paramIdx++, type);
            }
            stmt.setInt(paramIdx++, Math.max(1, limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(new RebateRecord(rs.getString("type"), rs.getDouble("amount"), rs.getString("source_name"), rs.getLong("created_at")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("获取返利记录失败: " + e.getMessage());
        }
        return records;
    }

    public CompletableFuture<List<RebateRecord>> getRebateRecords(UUID uuid, int limit) {
        return AsyncPool.supply(() -> getRebateRecordsSync(uuid, null, limit));
    }

    public record RebateRecord(String type, double amount, String sourceName, long createdAt) {
    }

    public CompletableFuture<Boolean> checkCrossServerDuplicate(String transactionKey) {
        return isPointsRebateRecordProcessed(transactionKey);
    }

    public CompletableFuture<Boolean> checkRebateDuplicate(UUID uuid, double amount) {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    /**
     * 今日已获得的返点总额（按 server.timezone 的自然日聚合，供每日上限校验）。
     */
    public CompletableFuture<Double> getTodayRebateTotal(UUID uuid) {
        java.time.ZoneId zone = plugin.getConfigManager().getTimeZone();
        return AsyncPool.supply(() -> getTodayRebateTotalSync(uuid, zone));
    }

    public double getTodayRebateTotalSync(UUID uuid, java.time.ZoneId zone) {
        long dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        String sql = "SELECT COALESCE(SUM(rebate_amount), 0) FROM " + tablePrefix
            + "points_rebate WHERE inviter_uuid = ? AND created_at >= ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, dayStart);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("查询今日返点总额失败: " + e.getMessage());
        }
        return 0.0;
    }

    public CompletableFuture<Void> updateLastPermissionGroup(UUID uuid, String group) {
        return AsyncPool.run(() -> {
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
        return AsyncPool.run(() -> {
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
        return AsyncPool.run(() -> {
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
        return AsyncPool.run(() -> {
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
        return AsyncPool.supply(() -> queryPlayersColumn(uuid, "bind_ip"));
    }

    public CompletableFuture<Void> resetPlayerData(UUID uuid) {
        return AsyncPool.run(() -> resetPlayerDataSync(uuid));
    }

    public void resetPlayerDataSync(UUID uuid) {
        try (Connection conn = getConnection()) {
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

            if (inviterUuid != null) {
                String updateInviterSql = "UPDATE " + tablePrefix + "players SET total_invites = total_invites - 1 WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(updateInviterSql)) {
                    stmt.setString(1, inviterUuid.toString());
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().warning("回滚邀请人计数失败: " + e.getMessage());
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM " + tablePrefix + "records WHERE invitee_uuid = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM " + tablePrefix + "pending_milestones WHERE player_uuid = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            }

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
                "unclaimed_rebate = 0, " +
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
            notifyChange(uuid, "reset");
        } catch (SQLException e) {
            plugin.getLogger().severe("重置玩家数据失败: " + e.getMessage());
        }
    }

    // ───────────────────────── 点券返点流水 ─────────────────────────

    /**
     * 充值返点流水占用：以 transaction_key 唯一键 INSERT 抢占处理权。
     * 插入成功 = 本服获得处理权；插入失败 = 该事件已被任意服务器处理或正在处理。
     * 这是跨服防二次发放的核心。
     */
    public boolean tryBeginRebateSync(String transactionKey, UUID playerUuid, double amount, UUID inviterUuid, double rebateAmount) {
        boolean isMySQL = "mysql".equalsIgnoreCase(plugin.getConfigManager().getDatabaseConfig().getString("database.type", "sqlite"));
        String sql = (isMySQL ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ")
            + tablePrefix + "points_rebate (transaction_key, player_uuid, amount, inviter_uuid, rebate_amount, created_at, status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, transactionKey);
            stmt.setString(2, playerUuid.toString());
            stmt.setDouble(3, amount);
            stmt.setString(4, inviterUuid != null ? inviterUuid.toString() : null);
            stmt.setDouble(5, rebateAmount);
            stmt.setLong(6, System.currentTimeMillis());
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().severe("占用返点流水失败: " + e.getMessage());
            return false;
        }
    }

    public CompletableFuture<Void> addPointsRebateRecord(String transactionKey, UUID playerUuid, double amount, UUID inviterUuid, double rebateAmount) {
        return AsyncPool.run(() -> {
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

    /**
     * 窗口期防重复：同一邀请人在窗口秒内已获得过相同金额的返点 → 视为重复推送。
     */
    public boolean hasRecentRebateSync(UUID inviterUuid, double rebateAmount, long sinceMillis) {
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "points_rebate "
            + "WHERE inviter_uuid = ? AND rebate_amount = ? AND created_at > ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, inviterUuid.toString());
            stmt.setDouble(2, rebateAmount);
            stmt.setLong(3, sinceMillis);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("检查窗口期返利失败: " + e.getMessage());
        }
        return false;
    }

    /** 写入一条返利事件流水（自动生成唯一 key，状态直接 PROCESSED），作为窗口期判重依据。 */
    public void insertRebateEventSync(UUID inviterUuid, UUID sourceUuid, double rechargeAmount,
                                      double rebateAmount, String sourcePlayer) {
        String key = "evt_" + java.util.UUID.randomUUID();
        boolean isMySQL = "mysql".equalsIgnoreCase(
            plugin.getConfigManager().getDatabaseConfig().getString("database.type", "sqlite"));
        String sql = (isMySQL ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ")
            + tablePrefix + "points_rebate "
            + "(transaction_key, player_uuid, amount, inviter_uuid, rebate_amount, created_at, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PROCESSED')";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setString(2, sourceUuid.toString());
            stmt.setDouble(3, rechargeAmount);
            stmt.setString(4, inviterUuid.toString());
            stmt.setDouble(5, rebateAmount);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("写入返利事件流水失败: " + e.getMessage());
        }
    }

    public boolean isPointsRebateRecordProcessedSync(String transactionKey) {
        String sql = "SELECT status FROM " + tablePrefix + "points_rebate WHERE transaction_key = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, transactionKey);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return "PROCESSED".equals(rs.getString("status"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("检查点券返点记录是否已处理失败: " + e.getMessage());
        }
        return false;
    }

    public CompletableFuture<Boolean> isPointsRebateRecordProcessed(String transactionKey) {
        return AsyncPool.supply(() -> isPointsRebateRecordProcessedSync(transactionKey));
    }

    public CompletableFuture<Void> markPointsRebateRecordProcessed(String transactionKey) {
        return AsyncPool.run(() -> {
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

    // ───────────────────────── 公告 ─────────────────────────

    public CompletableFuture<Void> addAnnouncement(String serverId, String message) {
        return AsyncPool.run(() -> {
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
        return AsyncPool.supply(() -> {
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
        return AsyncPool.run(() -> {
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

    // ───────────────────────── 排行榜 ─────────────────────────

    private List<PlayerData> getTopPlayersSync(String orderColumn, int limit) {
        List<PlayerData> players = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "players ORDER BY " + orderColumn + " DESC LIMIT ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerRow(UUID.fromString(rs.getString("uuid")), rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("获取排行榜玩家失败: " + e.getMessage());
        }
        return players;
    }

    public CompletableFuture<List<PlayerData>> getTopPlayersByInvites(int limit) {
        return AsyncPool.supply(() -> getTopPlayersSync("total_invites", limit));
    }

    public CompletableFuture<List<PlayerData>> getTopPlayersByContribution(int limit) {
        return AsyncPool.supply(() -> getTopPlayersSync("contribution_amount", limit));
    }

    private PlayerData mapPlayerRow(UUID uuid, ResultSet rs) throws SQLException {
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
