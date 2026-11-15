package com.alinvite.database.ledger;

import com.alinvite.manager.InviteQuotaPolicy;
import com.alinvite.manager.QuotaRule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Single-use invitation code ledger.
 *
 * <p>Every code that can ever be bound lives in {@code <prefix>invite_codes} with one of four
 * states: {@code AVAILABLE}, {@code RESERVED} (pre-invite token), {@code USED} and
 * {@code RETIRED} (legacy permanent strings, admin revocations). The row is the only authority:
 * a code is consumable exactly once because consumption is a conditional
 * {@code UPDATE ... WHERE status = ?} whose affected-row count decides the winner, which holds for
 * concurrent threads and for several servers sharing one MySQL database.
 *
 * <p>The class deliberately has no Bukkit/plugin dependency: callers supply a connection provider,
 * the table prefix, a code factory and the config-derived settings. That keeps the real production
 * SQL executable from plain JDBC tests.
 */
public final class InviteLedgerDao {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_RESERVED = "RESERVED";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_RETIRED = "RETIRED";

    /** Upper bound for the per-tick issuance loop, so a corrupted watermark cannot spin forever. */
    private static final int MAX_BOUNDARIES_PER_TICK = 4096;
    private static final int MAX_CODE_ATTEMPTS = 32;

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    public interface CodeFactory {
        String next();
    }

    /** Config-derived switches needed by the bind transaction. */
    public record BindSettings(boolean quotaEnabled,
                               int maxInvites,
                               boolean ipRestrictionEnabled,
                               int maxInvitesPerIp,
                               boolean preventSelfIp,
                               boolean flexibleMilestone,
                               boolean flexibleRebate,
                               boolean flexibleGift) {

        public static BindSettings defaults() {
            return new BindSettings(true, 0, true, 1, true, false, true, false);
        }
    }

    public enum BindStatus {
        SUCCESS,
        DUPLICATE,
        QUOTA_EXHAUSTED,
        INVITER_LIMIT_REACHED,
        INVITEE_VETERAN,
        IP_LIMIT,
        CODE_NOT_FOUND,
        CODE_NOT_AVAILABLE,
        CODE_RESERVED,
        CODE_OWNER_MISMATCH,
        LEGACY_RETIRED,
        ADMISSION_CLOSED,
        ERROR
    }

    public record BindRequest(UUID inviterUuid, UUID inviteeUuid, String inviteeIp, String inviterIp,
                              String inviteeName, String code, String pendingTargetName,
                              boolean consumeQuota, BindSettings settings) {
    }

    public static final class BindOutcome {
        public final BindStatus status;
        public final int totalInvites;
        public final int remainingSlots;
        public final boolean milestoneEnabled;
        public final boolean rebateEnabled;
        public final boolean giftEnabled;

        public BindOutcome(BindStatus status, int totalInvites, int remainingSlots,
                           boolean milestoneEnabled, boolean rebateEnabled, boolean giftEnabled) {
            this.status = status;
            this.totalInvites = totalInvites;
            this.remainingSlots = remainingSlots;
            this.milestoneEnabled = milestoneEnabled;
            this.rebateEnabled = rebateEnabled;
            this.giftEnabled = giftEnabled;
        }

        static BindOutcome failure(BindStatus status) {
            return new BindOutcome(status, 0, 0, true, true, true);
        }

        static BindOutcome failure(BindStatus status, int totalInvites, int remainingSlots) {
            return new BindOutcome(status, totalInvites, remainingSlots, true, true, true);
        }

        public boolean success() {
            return status == BindStatus.SUCCESS;
        }
    }

    public enum TickStatus {
        UPDATED,
        NOT_REGISTERED,
        SKIPPED_UNREGISTERED,
        ERROR
    }

    public record TickResult(TickStatus status, int granted, int held, int available,
                             int nextBoundary, String policyKey) {
        static TickResult error() {
            return new TickResult(TickStatus.ERROR, 0, 0, 0, 0, null);
        }
    }

    public enum MigrationStatus {
        MIGRATED,
        ALREADY_MIGRATED,
        NOT_REGISTERED,
        ERROR
    }

    public record MigrationResult(MigrationStatus status, int convertedCodes, int retiredLegacy,
                                  int reservedPending, int available, String legacyCode) {
        static MigrationResult error() {
            return new MigrationResult(MigrationStatus.ERROR, 0, 0, 0, 0, null);
        }
    }

    public enum ReserveStatus {
        SUCCESS,
        ALREADY_RESERVED,
        QUOTA_EXHAUSTED,
        INVITER_NOT_FOUND,
        INVITER_LIMIT_REACHED,
        ERROR
    }

    public record ReserveResult(ReserveStatus status, int remainingSlots, String reservedCode) {
        static ReserveResult error() {
            return new ReserveResult(ReserveStatus.ERROR, 0, null);
        }
    }

    /** A ledger row as read inside a transaction. */
    public record CodeRow(String code, String ownerUuid, String status, String reservedTarget,
                          int boundaryIndex) {
    }

    /** Mutable cursor used by the boundary loop. */
    private static final class Cursor {
        int next;
        int granted;
    }

    private record PlayerRow(int totalInvites, int slots, String policyKey, int nextBoundary,
                             long highWaterMinutes, boolean legacyMigrated,
                             String legacyCode, boolean personalCodeReady,
                             int legacyPeriodsGranted, String legacyBoundInviteCode) {
    }

    private final ConnectionProvider connections;
    private final String prefix;
    private final boolean mysql;
    private final CodeFactory codes;

    public InviteLedgerDao(ConnectionProvider connections, String tablePrefix,
                           boolean mysql, CodeFactory codeFactory) {
        this.connections = connections;
        this.prefix = tablePrefix == null ? "" : tablePrefix;
        this.mysql = mysql;
        this.codes = codeFactory;
    }

    /** Creates only admitted accounts; this operation never issues a code. */
    public void ensureEligiblePlayer(UUID owner, boolean veteranAccess) {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                lockEligiblePlayer(conn, owner, veteranAccess);
                conn.commit();
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化已准入玩家账户失败", e);
        }
    }

    private PlayerRow lockEligiblePlayer(Connection conn, UUID owner, boolean veteranAccess) throws SQLException {
        PlayerRow row = lockPlayer(conn, owner);
        if (row != null) {
            return row;
        }
        if (!veteranAccess && !hasRegistrationClaim(conn, owner)
                && !exists(conn, "SELECT 1 FROM " + t("records") + " WHERE invitee_uuid = ?", owner.toString())) {
            return null;
        }
        String sql = insertIgnore() + t("players")
            + " (uuid, invite_code, personal_code_ready, invite_slots, total_invites,"
            + " last_slot_replenish, quota_periods_granted, quota_next_boundary,"
            + " quota_high_water_minutes, quota_legacy_migrated, created_at)"
            + " VALUES (?, ?, ?, 0, 0, 0, 0, 1, 0, 1, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, owner.toString());
            stmt.setString(2, "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 15));
            stmt.setBoolean(3, veteranAccess);
            stmt.setLong(4, System.currentTimeMillis());
            stmt.executeUpdate();
        }
        return lockPlayer(conn, owner);
    }

    /** Convert inventory before accepting logins, including pre-invites from offline owners. */
    public void migrateExistingInventories(QuotaRule fallback) throws SQLException {
        List<UUID> owners = new ArrayList<>();
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT uuid FROM " + t("players")
                 + " WHERE quota_legacy_migrated = 0")) {
            while (rs.next()) {
                owners.add(UUID.fromString(rs.getString(1)));
            }
        }
        for (UUID owner : owners) {
            migrateLegacy(owner, fallback, Double.NaN);
        }
    }

    private String t(String name) {
        return prefix + name;
    }

    // ───────────────────────── schema ─────────────────────────

    /** Idempotent creation of the ledger tables plus the columns it owns on legacy tables. */
    public void ensureSchema() throws SQLException {
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS `%sinvite_codes` (
                    `code` VARCHAR(32) PRIMARY KEY,
                    `owner_uuid` VARCHAR(36) NOT NULL,
                    `status` VARCHAR(16) NOT NULL,
                    `policy_key` VARCHAR(64),
                    `boundary_index` INT NOT NULL DEFAULT 0,
                    `legacy` INT NOT NULL DEFAULT 0,
                    `issued_at` BIGINT NOT NULL,
                    `reserved_target` VARCHAR(16),
                    `reserved_at` BIGINT,
                    `used_at` BIGINT,
                    `used_by_uuid` VARCHAR(36),
                    `retired_reason` VARCHAR(32)
                )
                """.formatted(prefix));
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS `%slegacy_code_archive` (
                    `uuid` VARCHAR(36) PRIMARY KEY,
                    `legacy_code` VARCHAR(16),
                    `legacy_slots` INT NOT NULL DEFAULT 0,
                    `legacy_periods_granted` INT NOT NULL DEFAULT 0,
                    `legacy_personal_code_ready` INT NOT NULL DEFAULT 0,
                    `legacy_bound_invite_code` VARCHAR(16),
                    `observed_minutes` BIGINT NOT NULL DEFAULT 0,
                    `converted_codes` INT NOT NULL DEFAULT 0,
                    `reserved_codes` INT NOT NULL DEFAULT 0,
                    `migrated_at` BIGINT NOT NULL
                )
                """.formatted(prefix));
        }
        createIndex("idx_" + prefix + "invite_codes_owner",
            "invite_codes", "owner_uuid, status");
        createIndex("idx_" + prefix + "invite_codes_status",
            "invite_codes", "status");
        createIndex("idx_" + prefix + "invite_codes_reserved",
            "invite_codes", "reserved_target");
        addColumnIfMissing("players", "quota_policy_key", "VARCHAR(64)");
        addColumnIfMissing("players", "quota_next_boundary", "INT NOT NULL DEFAULT -1");
        addColumnIfMissing("players", "quota_high_water_minutes", "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing("players", "quota_legacy_migrated", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("pending_invites", "reserved_code", "VARCHAR(32)");
    }

    private void createIndex(String indexName, String table, String columns) throws SQLException {
        if (mysql) {
            try (Connection conn = connections.open()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SHOW INDEX FROM " + t(table) + " WHERE Key_name = ?")) {
                    ps.setString(1, indexName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return;
                        }
                    }
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("CREATE INDEX " + indexName + " ON " + t(table) + "(" + columns + ")");
                }
            }
            return;
        }
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + t(table) + "(" + columns + ")");
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        if (columnExists(table, column)) {
            return;
        }
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + t(table) + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        if (mysql) {
            try (Connection conn = connections.open();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                         + " AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
                ps.setString(1, t(table));
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        }
        try (Connection conn = connections.open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(\"" + t(table) + "\")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ───────────────────────── queries ─────────────────────────

    public List<String> listAvailable(UUID owner) {
        List<String> result = new ArrayList<>();
        try (Connection conn = connections.open()) {
            result.addAll(listAvailable(conn, owner));
        } catch (SQLException e) {
            throw new IllegalStateException("查询可用邀请码失败", e);
        }
        return result;
    }

    public List<String> listAvailable(Connection conn, UUID owner) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT code FROM " + t("invite_codes")
            + " WHERE owner_uuid = ? AND status = ? ORDER BY issued_at, code";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, owner.toString());
            stmt.setString(2, STATUS_AVAILABLE);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }

    public String firstAvailable(UUID owner) {
        try (Connection conn = connections.open()) {
            return firstAvailable(conn, owner);
        } catch (SQLException e) {
            throw new IllegalStateException("查询可用邀请码失败", e);
        }
    }

    public String firstAvailable(Connection conn, UUID owner) throws SQLException {
        String sql = "SELECT code FROM " + t("invite_codes")
            + " WHERE owner_uuid = ? AND status = ? ORDER BY issued_at, code";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, owner.toString());
            stmt.setString(2, STATUS_AVAILABLE);
            stmt.setMaxRows(1);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public int countAvailable(Connection conn, UUID owner) throws SQLException {
        return count(conn, "SELECT COUNT(*) FROM " + t("invite_codes")
            + " WHERE owner_uuid = ? AND status = ?", owner.toString(), STATUS_AVAILABLE);
    }

    public int countHeld(Connection conn, UUID owner) throws SQLException {
        return count(conn, "SELECT COUNT(*) FROM " + t("invite_codes")
            + " WHERE owner_uuid = ? AND status IN (?, ?)",
            owner.toString(), STATUS_AVAILABLE, STATUS_RESERVED);
    }

    public int countAvailable(UUID owner) {
        try (Connection conn = connections.open()) {
            return countAvailable(conn, owner);
        } catch (SQLException e) {
            throw new IllegalStateException("统计可用邀请码失败", e);
        }
    }

    /** Owner of a code that is currently bindable by string; null when unknown or not available. */
    public String resolveAvailableOwner(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try (Connection conn = connections.open();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT owner_uuid FROM " + t("invite_codes") + " WHERE code = ? AND status = ?")) {
            stmt.setString(1, code);
            stmt.setString(2, STATUS_AVAILABLE);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询邀请码归属失败", e);
        }
    }

    /** Ledger state of a code, or null when the string was never issued by this ledger. */
    public CodeRow findCode(Connection conn, String code) throws SQLException {
        String sql = "SELECT code, owner_uuid, status, reserved_target, boundary_index FROM "
            + t("invite_codes") + " WHERE code = ?" + forUpdate();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, code);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CodeRow(rs.getString("code"), rs.getString("owner_uuid"),
                    rs.getString("status"), rs.getString("reserved_target"),
                    rs.getInt("boundary_index"));
            }
        }
    }

    public boolean codeExists(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        try (Connection conn = connections.open();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT 1 FROM " + t("invite_codes") + " WHERE code = ? AND status = ?")) {
            stmt.setString(1, code);
            stmt.setString(2, STATUS_AVAILABLE);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询邀请码失败", e);
        }
    }

    // ───────────────────────── bind ─────────────────────────

    /**
     * Consumes exactly one code and records the invite in a single transaction.
     * Lock order is fixed: owner player row → pending reservation → code row.
     */
    public BindOutcome bind(BindRequest request) {
        BindSettings settings = request.settings() == null ? BindSettings.defaults() : request.settings();
        long now = System.currentTimeMillis();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);

                PlayerRow owner = lockPlayer(conn, request.inviterUuid());
                if (owner == null) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.INVITER_LIMIT_REACHED);
                }

                String reservedCode = null;
                if (request.pendingTargetName() != null) {
                    String pendingSql = "SELECT inviter_uuid, reserved_code FROM " + t("pending_invites")
                        + " WHERE target_name = ?" + forUpdate();
                    boolean matches = false;
                    try (PreparedStatement stmt = conn.prepareStatement(pendingSql)) {
                        stmt.setString(1, request.pendingTargetName());
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                matches = request.inviterUuid().toString().equals(rs.getString("inviter_uuid"));
                                reservedCode = rs.getString("reserved_code");
                            }
                        }
                    }
                    if (!matches) {
                        conn.rollback();
                        return BindOutcome.failure(BindStatus.DUPLICATE,
                            owner.totalInvites, owner.slots);
                    }
                }

                CodeRow code = findCode(conn, request.code());
                if (code == null) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.CODE_NOT_FOUND,
                        owner.totalInvites, owner.slots);
                }
                if (!request.inviterUuid().toString().equals(code.ownerUuid())) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.CODE_OWNER_MISMATCH,
                        owner.totalInvites, owner.slots);
                }
                if (STATUS_RETIRED.equals(code.status())) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.LEGACY_RETIRED,
                        owner.totalInvites, owner.slots);
                }
                if (STATUS_USED.equals(code.status())) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.CODE_NOT_AVAILABLE,
                        owner.totalInvites, owner.slots);
                }
                if (STATUS_RESERVED.equals(code.status())) {
                    // A reserved token can only be consumed by the matching pre-invite claim.
                    // A pre-migration pending row has no token yet: the pending row itself is the lock,
                    // so an AVAILABLE code chosen for it is accepted below.
                    if (request.pendingTargetName() == null
                        || (reservedCode != null && !reservedCode.equalsIgnoreCase(code.code()))) {
                        conn.rollback();
                        return BindOutcome.failure(BindStatus.CODE_RESERVED,
                            owner.totalInvites, owner.slots);
                    }
                } else if (!STATUS_AVAILABLE.equals(code.status())) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.CODE_NOT_AVAILABLE,
                        owner.totalInvites, owner.slots);
                }

                if (settings.maxInvites() > 0 && owner.totalInvites >= settings.maxInvites()) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.INVITER_LIMIT_REACHED,
                        owner.totalInvites, owner.slots);
                }

                boolean sameIp = request.inviterIp() != null
                    && request.inviterIp().equals(request.inviteeIp());
                if (settings.ipRestrictionEnabled() && settings.preventSelfIp() && sameIp) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.IP_LIMIT,
                        owner.totalInvites, owner.slots);
                }

                int ipCount = 0;
                if (request.inviteeIp() != null && !request.inviteeIp().isBlank()) {
                    bumpIpClaim(conn, request.inviteeIp(), now);
                    ipCount = count(conn, "SELECT bind_count FROM " + t("ip_claims") + " WHERE ip = ?",
                        request.inviteeIp());
                }
                if (settings.ipRestrictionEnabled() && settings.maxInvitesPerIp() > 0
                    && ipCount > settings.maxInvitesPerIp()) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.IP_LIMIT,
                        owner.totalInvites, owner.slots);
                }

                boolean openRegistered = hasRegistrationClaim(conn, request.inviteeUuid());
                Boolean inviteeReady = inviteePersonalCodeReady(conn, request.inviteeUuid());
                if (inviteeReady != null && inviteeReady && !openRegistered) {
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.INVITEE_VETERAN,
                        owner.totalInvites, owner.slots);
                }

                String expectedStatus = STATUS_RESERVED.equals(code.status())
                    ? STATUS_RESERVED : STATUS_AVAILABLE;
                if (consume(conn, request.code(), expectedStatus, request.inviteeUuid(), now) != 1) {
                    // Lost the race against another server/thread.
                    conn.rollback();
                    return BindOutcome.failure(BindStatus.CODE_NOT_AVAILABLE,
                        owner.totalInvites, owner.slots);
                }

                String insertSql = insertIgnore() + t("records")
                    + " (inviter_uuid, invitee_uuid, invitee_ip, invitee_name, invited_at)"
                    + " VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, request.inviterUuid().toString());
                    stmt.setString(2, request.inviteeUuid().toString());
                    stmt.setString(3, request.inviteeIp());
                    stmt.setString(4, request.inviteeName());
                    stmt.setLong(5, now);
                    if (stmt.executeUpdate() != 1) {
                        conn.rollback();
                        return BindOutcome.failure(BindStatus.DUPLICATE,
                            owner.totalInvites, owner.slots);
                    }
                }

                boolean flexibleSameIp = sameIp && !settings.ipRestrictionEnabled();
                boolean milestoneEnabled = !flexibleSameIp || settings.flexibleMilestone();
                boolean rebateEnabled = !flexibleSameIp || settings.flexibleRebate();
                boolean giftEnabled = !flexibleSameIp || settings.flexibleGift();

                if (inviteeReady != null) {
                    String updateInvitee = openRegistered
                        ? "UPDATE " + t("players") + " SET bound_invite_code = ?, bind_ip = ?,"
                            + " milestone_enabled = ?, rebate_enabled = ?, gift_enabled = ? WHERE uuid = ?"
                        : "UPDATE " + t("players") + " SET bound_invite_code = ?, personal_code_ready = 0,"
                            + " bind_ip = ?, milestone_enabled = ?, rebate_enabled = ?, gift_enabled = ?"
                            + " WHERE uuid = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(updateInvitee)) {
                        stmt.setString(1, request.code());
                        stmt.setString(2, request.inviteeIp());
                        stmt.setBoolean(3, milestoneEnabled);
                        stmt.setBoolean(4, rebateEnabled);
                        stmt.setBoolean(5, giftEnabled);
                        stmt.setString(6, request.inviteeUuid().toString());
                        stmt.executeUpdate();
                    }
                } else {
                    String placeholder = "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
                    String createInvitee = "INSERT INTO " + t("players")
                        + " (uuid, invite_code, bound_invite_code, personal_code_ready, total_invites,"
                        + " invite_slots, last_slot_replenish, quota_periods_granted, quota_next_boundary,"
                        + " quota_high_water_minutes, quota_legacy_migrated, bind_ip, milestone_enabled,"
                        + " rebate_enabled, gift_enabled, created_at)"
                        + " VALUES (?, ?, ?, 0, 0, 0, ?, 0, 1, 0, 1, ?, ?, ?, ?, ?)";
                    try (PreparedStatement stmt = conn.prepareStatement(createInvitee)) {
                        stmt.setString(1, request.inviteeUuid().toString());
                        stmt.setString(2, placeholder);
                        stmt.setString(3, request.code());
                        stmt.setLong(4, now);
                        stmt.setString(5, request.inviteeIp());
                        stmt.setBoolean(6, milestoneEnabled);
                        stmt.setBoolean(7, rebateEnabled);
                        stmt.setBoolean(8, giftEnabled);
                        stmt.setLong(9, now);
                        stmt.executeUpdate();
                    }
                }

                int newTotal = owner.totalInvites + 1;
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("players")
                        + " SET total_invites = ?, last_invite_time = ? WHERE uuid = ?")) {
                    stmt.setInt(1, newTotal);
                    stmt.setLong(2, now);
                    stmt.setString(3, request.inviterUuid().toString());
                    if (stmt.executeUpdate() != 1) {
                        conn.rollback();
                        return BindOutcome.failure(BindStatus.ERROR, owner.totalInvites, owner.slots);
                    }
                }

                if (request.pendingTargetName() != null) {
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + t("pending_invites")
                            + " WHERE target_name = ? AND inviter_uuid = ?")) {
                        stmt.setString(1, request.pendingTargetName());
                        stmt.setString(2, request.inviterUuid().toString());
                        if (stmt.executeUpdate() != 1) {
                            conn.rollback();
                            return BindOutcome.failure(BindStatus.DUPLICATE, owner.totalInvites, owner.slots);
                        }
                    }
                }

                upsertBoundPlayerName(conn, request.inviteeName(), request.inviteeUuid(),
                    request.inviterUuid(), now);
                int available = syncSlots(conn, request.inviterUuid());

                conn.commit();
                return new BindOutcome(BindStatus.SUCCESS, newTotal, available,
                    milestoneEnabled, rebateEnabled, giftEnabled);
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("邀请绑定事务失败: " + e.getMessage(), e);
        }
    }

    // ───────────────────────── reserve / release ─────────────────────────

    /** Reserves one available code for a pre-invite target; the reservation counts toward the cap. */
    public ReserveResult reserve(UUID inviterUuid, String inviterIp, String targetName,
                                 String displayName, int maxInvites) {
        long now = System.currentTimeMillis();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                PlayerRow owner = lockPlayer(conn, inviterUuid);
                if (owner == null) {
                    conn.rollback();
                    return new ReserveResult(ReserveStatus.INVITER_NOT_FOUND, 0, null);
                }
                int pendingCount = count(conn, "SELECT COUNT(*) FROM " + t("pending_invites")
                    + " WHERE inviter_uuid = ?", inviterUuid.toString());
                if (maxInvites > 0 && owner.totalInvites + pendingCount >= maxInvites) {
                    conn.rollback();
                    return new ReserveResult(ReserveStatus.INVITER_LIMIT_REACHED, owner.slots, null);
                }

                String code = firstAvailable(conn, inviterUuid);
                if (code == null) {
                    conn.rollback();
                    return new ReserveResult(ReserveStatus.QUOTA_EXHAUSTED, 0, null);
                }
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("invite_codes")
                        + " SET status = ?, reserved_target = ?, reserved_at = ?"
                        + " WHERE code = ? AND status = ?")) {
                    stmt.setString(1, STATUS_RESERVED);
                    stmt.setString(2, targetName);
                    stmt.setLong(3, now);
                    stmt.setString(4, code);
                    stmt.setString(5, STATUS_AVAILABLE);
                    if (stmt.executeUpdate() != 1) {
                        conn.rollback();
                        return new ReserveResult(ReserveStatus.QUOTA_EXHAUSTED, 0, null);
                    }
                }

                String insert = insertIgnore() + t("pending_invites")
                    + " (target_name, target_display_name, inviter_uuid, inviter_ip, created_at, reserved_code)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insert)) {
                    stmt.setString(1, targetName);
                    stmt.setString(2, displayName);
                    stmt.setString(3, inviterUuid.toString());
                    stmt.setString(4, inviterIp);
                    stmt.setLong(5, now);
                    stmt.setString(6, code);
                    if (stmt.executeUpdate() != 1) {
                        conn.rollback();
                        return new ReserveResult(ReserveStatus.ALREADY_RESERVED, owner.slots, null);
                    }
                }
                int remaining = syncSlots(conn, inviterUuid);
                conn.commit();
                return new ReserveResult(ReserveStatus.SUCCESS, remaining, code);
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("预邀请预留事务失败: " + e.getMessage(), e);
        }
    }

    /**
     * Releases a pre-invite reservation and returns its token to AVAILABLE.
     * Idempotent: a second call finds no pending row and changes nothing, so no stock is duplicated.
     *
     * @return true when a reservation was actually released by this call
     */
    public boolean release(UUID inviterUuid, String targetName) {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                PlayerRow owner = lockPlayer(conn, inviterUuid);
                if (owner == null) {
                    conn.rollback();
                    return false;
                }
                String reservedCode = null;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT reserved_code FROM "
                        + t("pending_invites") + " WHERE target_name = ? AND inviter_uuid = ?" + forUpdate())) {
                    stmt.setString(1, targetName);
                    stmt.setString(2, inviterUuid.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            reservedCode = rs.getString(1);
                        }
                    }
                }
                int deleted;
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + t("pending_invites")
                        + " WHERE target_name = ? AND inviter_uuid = ?")) {
                    stmt.setString(1, targetName);
                    stmt.setString(2, inviterUuid.toString());
                    deleted = stmt.executeUpdate();
                }
                if (deleted != 1) {
                    conn.rollback();
                    return false;
                }
                if (reservedCode != null && !reservedCode.isBlank()) {
                    try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("invite_codes")
                            + " SET status = ?, reserved_target = NULL, reserved_at = NULL"
                            + " WHERE code = ? AND status = ? AND reserved_target = ?")) {
                        stmt.setString(1, STATUS_AVAILABLE);
                        stmt.setString(2, reservedCode);
                        stmt.setString(3, STATUS_RESERVED);
                        stmt.setString(4, targetName);
                        stmt.executeUpdate();
                    }
                }
                syncSlots(conn, inviterUuid);
                conn.commit();
                return true;
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("释放预邀请事务失败: " + e.getMessage(), e);
        }
    }

    // ───────────────────────── issuance ─────────────────────────

    /**
     * Advances the persisted quota watermark for one player and mints every code the player has
     * earned since the last tick.
     *
     * <p>Skipped periods are consumed by the watermark (no hidden backlog), a policy switch settles
     * the previous rule first and then baselines the new rule at the current boundary, and the
     * absolute playtime high-water mark never moves backwards.
     */
    public TickResult applyQuotaTick(UUID owner, double observedHours, QuotaRule rule,
                                     Function<String, QuotaRule> ruleLookup) {
        if (rule == null) {
            return TickResult.error();
        }
        long now = System.currentTimeMillis();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                PlayerRow row = lockEligiblePlayer(conn, owner, false);
                if (row == null) {
                    conn.rollback();
                    return new TickResult(TickStatus.NOT_REGISTERED, 0, 0, 0, 0, null);
                }
                if (!isRegistered(conn, owner, row)) {
                    // Gate-locked newcomers must not farm codes before they are registered.
                    conn.rollback();
                    return new TickResult(TickStatus.SKIPPED_UNREGISTERED, 0, 0, 0,
                        row.nextBoundary, row.policyKey);
                }

                long observedMinutes = Math.max(row.highWaterMinutes,
                    (long) Math.floor(Math.max(0.0, observedHours) * 60.0 + 1.0e-7));
                double hours = observedMinutes / 60.0;

                Cursor cursor = new Cursor();
                String policyKey;
                if (row.policyKey == null || row.policyKey.isBlank()) {
                    policyKey = rule.key();
                    cursor.next = row.nextBoundary > 0 ? row.nextBoundary
                        : InviteQuotaPolicy.firstGrantableBoundary(hours, rule.hoursPerSlot());
                } else if (!row.policyKey.equals(rule.key())) {
                    QuotaRule previous = ruleLookup == null ? null : ruleLookup.apply(row.policyKey);
                    if (previous == null) {
                        previous = rule;
                    }
                    // 1) settle the previous policy up to the observed hours
                    cursor.next = row.nextBoundary > 0 ? row.nextBoundary : 1;
                    grantLoop(conn, owner, previous, hours, cursor, now);
                    // 2) baseline the new policy at the current boundary (no retroactive replay)
                    policyKey = rule.key();
                    cursor.next = InviteQuotaPolicy.firstGrantableBoundary(hours, rule.hoursPerSlot());
                } else {
                    policyKey = row.policyKey;
                    cursor.next = row.nextBoundary > 0 ? row.nextBoundary : 1;
                }
                grantLoop(conn, owner, rule, hours, cursor, now);

                try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("players")
                        + " SET quota_policy_key = ?, quota_next_boundary = ?,"
                        + " quota_high_water_minutes = ? WHERE uuid = ?")) {
                    stmt.setString(1, policyKey);
                    stmt.setInt(2, cursor.next);
                    stmt.setLong(3, observedMinutes);
                    stmt.setString(4, owner.toString());
                    if (stmt.executeUpdate() != 1) {
                        conn.rollback();
                        return TickResult.error();
                    }
                }
                int available = syncSlots(conn, owner);
                int held = countHeld(conn, owner);
                conn.commit();
                return new TickResult(TickStatus.UPDATED, cursor.granted, held, available,
                    cursor.next, policyKey);
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("发放邀请码事务失败: " + e.getMessage(), e);
        }
    }

    /** Grants every boundary from {@code cursor.next} upward that the observed hours have reached. */
    private void grantLoop(Connection conn, UUID owner, QuotaRule rule, double hours,
                           Cursor cursor, long now) throws SQLException {
        int last = InviteQuotaPolicy.boundaryIndex(hours, rule.hoursPerSlot());
        if (rule.mode() == InviteQuotaPolicy.Mode.ONCE) {
            last = Math.min(1, last);
        }
        int index = Math.max(1, cursor.next);
        int held = countHeld(conn, owner);
        while (index <= last && index < Integer.MAX_VALUE) {
            if (!rule.unlimited() && held >= rule.maxCodes()) {
                // All remaining reached periods occurred while full: consume them with no backlog.
                index = last == Integer.MAX_VALUE ? Integer.MAX_VALUE : last + 1;
                break;
            }
            mint(conn, owner, rule, index, now, false);
            cursor.granted++;
            held++;
            index++;
        }
        cursor.next = index;
    }

    private String mint(Connection conn, UUID owner, QuotaRule rule, int boundaryIndex,
                        long now, boolean legacy) throws SQLException {
        String sql = "INSERT INTO " + t("invite_codes")
            + " (code, owner_uuid, status, policy_key, boundary_index, legacy, issued_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String candidate = codes == null ? null : codes.next();
            if (candidate == null || candidate.isBlank()) {
                throw new SQLException("邀请码生成器未返回有效码，事务回滚并保留发放节点");
            }
            candidate = candidate.toUpperCase(Locale.ROOT);
            if (exists(conn, "SELECT 1 FROM " + t("players") + " WHERE UPPER(invite_code) = ?", candidate)
                    || exists(conn, "SELECT 1 FROM " + t("players")
                        + " WHERE UPPER(bound_invite_code) = ?", candidate)) {
                continue;
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, candidate);
                stmt.setString(2, owner.toString());
                stmt.setString(3, STATUS_AVAILABLE);
                stmt.setString(4, rule.key());
                stmt.setInt(5, boundaryIndex);
                stmt.setInt(6, legacy ? 1 : 0);
                stmt.setLong(7, now);
                stmt.executeUpdate();
                return candidate;
            } catch (SQLException e) {
                if (!isConstraintViolation(e)) {
                    throw e;
                }
            }
        }
        throw new SQLException("唯一邀请码生成连续冲突，事务回滚并保留库存和节点");
    }

    // ───────────────────────── migration ─────────────────────────

    /**
     * One-time, idempotent, audit-preserving migration of one player's legacy permanent code,
     * legacy slot counter and pending pre-invites.
     *
     * <p>Historical rows ({@code records}, {@code bound_invite_code}, statistics) are never touched.
     * The legacy string is archived and retired forever; positive slots become fresh unused codes
     * (excess preserved); pending reservations become reserved tokens with their targets intact.
     * The quota watermark is baselined at the observed hours so nothing is replayed.
     */
    public MigrationResult migrateLegacy(UUID owner, QuotaRule rule, double observedHours) {
        if (rule == null) {
            return MigrationResult.error();
        }
        long now = System.currentTimeMillis();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                PlayerRow row = lockEligiblePlayer(conn, owner, false);
                if (row == null) {
                    conn.rollback();
                    return new MigrationResult(MigrationStatus.NOT_REGISTERED, 0, 0, 0, 0, null);
                }
                if (!isRegistered(conn, owner, row)) {
                    conn.rollback();
                    return new MigrationResult(MigrationStatus.NOT_REGISTERED, 0, 0, 0, 0, null);
                }
                if (row.legacyMigrated) {
                    conn.rollback();
                    return new MigrationResult(MigrationStatus.ALREADY_MIGRATED, 0, 0, 0,
                        countAvailable(conn, owner), row.legacyCode);
                }

                boolean deferBaseline = !Double.isFinite(observedHours);
                long observedMinutes = Math.max(row.highWaterMinutes, deferBaseline ? 0L
                    : (long) Math.floor(Math.max(0.0, observedHours) * 60.0 + 1.0e-7));

                // Audit snapshots are immutable across reset/re-migration.
                boolean archiveInserted;
                try (PreparedStatement stmt = conn.prepareStatement(insertIgnore() + t("legacy_code_archive")
                        + " (uuid, legacy_code, legacy_slots, legacy_periods_granted,"
                        + " legacy_personal_code_ready, legacy_bound_invite_code, observed_minutes,"
                        + " converted_codes, reserved_codes, migrated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?)")) {
                    stmt.setString(1, owner.toString());
                    stmt.setString(2, row.legacyCode);
                    stmt.setInt(3, Math.max(0, row.slots));
                    stmt.setInt(4, Math.max(0, row.legacyPeriodsGranted));
                    stmt.setInt(5, row.personalCodeReady ? 1 : 0);
                    stmt.setString(6, row.legacyBoundInviteCode);
                    stmt.setLong(7, observedMinutes);
                    stmt.setLong(8, now);
                    archiveInserted = stmt.executeUpdate() == 1;
                }

                // Retire the old permanent string forever.
                int retired = 0;
                if (row.legacyCode != null && !row.legacyCode.isBlank()) {
                    retired = retireCode(conn, owner, row.legacyCode, "LEGACY", now);
                }

                // Convert positive legacy slots into fresh unused codes (grandfathered excess kept).
                int legacySlots = Math.max(0, row.slots);
                int converted = 0;
                for (int i = 0; i < legacySlots; i++) {
                    if (mint(conn, owner, rule, 0, now, true) != null) {
                        converted++;
                    }
                }

                // Migrate existing pre-invites into unique reserved tokens, preserving targets.
                int reserved = 0;
                List<String> targets = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT target_name FROM " + t("pending_invites") + " WHERE inviter_uuid = ?"
                            + " ORDER BY created_at")) {
                    stmt.setString(1, owner.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            targets.add(rs.getString(1));
                        }
                    }
                }
                for (String target : targets) {
                    if (target == null || target.isBlank()) {
                        continue;
                    }
                    String existing = null;
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "SELECT reserved_code FROM " + t("pending_invites") + " WHERE target_name = ?")) {
                        stmt.setString(1, target);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                existing = rs.getString(1);
                            }
                        }
                    }
                    if (existing != null && !existing.isBlank()) {
                        continue;
                    }
                    // Old /add already deducted its slot. Preserve that reservation independently
                    // of the remaining available legacy inventory.
                    String code = mint(conn, owner, rule, 0, now, true);
                    try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("invite_codes")
                            + " SET status = ?, reserved_target = ?, reserved_at = ?"
                            + " WHERE code = ? AND status = ?")) {
                        stmt.setString(1, STATUS_RESERVED);
                        stmt.setString(2, target);
                        stmt.setLong(3, now);
                        stmt.setString(4, code);
                        stmt.setString(5, STATUS_AVAILABLE);
                        if (stmt.executeUpdate() != 1) {
                            continue;
                        }
                    }
                    try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("pending_invites")
                            + " SET reserved_code = ? WHERE target_name = ? AND inviter_uuid = ?")) {
                        stmt.setString(1, code);
                        stmt.setString(2, target);
                        stmt.setString(3, owner.toString());
                        stmt.executeUpdate();
                    }
                    reserved++;
                }

                // Baseline the watermark: migrated history is never replayed, while a player with no
                // legacy artifacts at all keeps the normal schedule from the first boundary.
                boolean hasLegacy = (row.legacyCode != null && !row.legacyCode.isBlank())
                    || row.slots > 0 || row.legacyPeriodsGranted > 0;
                int next = deferBaseline ? -1 : hasLegacy
                    ? InviteQuotaPolicy.firstGrantableBoundary(observedMinutes / 60.0, rule.hoursPerSlot())
                    : 1;
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("players")
                        + " SET quota_policy_key = ?, quota_next_boundary = ?,"
                        + " quota_high_water_minutes = ?, quota_legacy_migrated = 1 WHERE uuid = ?")) {
                    stmt.setString(1, deferBaseline ? null : rule.key());
                    stmt.setInt(2, next);
                    stmt.setLong(3, observedMinutes);
                    stmt.setString(4, owner.toString());
                    stmt.executeUpdate();
                }
                int available = syncSlots(conn, owner);

                if (archiveInserted) try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("legacy_code_archive")
                        + " SET converted_codes = ?, reserved_codes = ? WHERE uuid = ?")) {
                    stmt.setInt(1, converted);
                    stmt.setInt(2, reserved);
                    stmt.setString(3, owner.toString());
                    stmt.executeUpdate();
                }

                conn.commit();
                return new MigrationResult(MigrationStatus.MIGRATED, converted, retired,
                    reserved, available, row.legacyCode);
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("旧数据迁移事务失败: " + e.getMessage(), e);
        }
    }

    // ───────────────────────── revocation ─────────────────────────

    /** Retires up to {@code count} AVAILABLE codes (oldest first); used by punishment. */
    public int retireAvailable(UUID owner, int count, String reason) {
        if (count <= 0) {
            return 0;
        }
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                lockPlayer(conn, owner);
                int retired = 0;
                for (String code : listAvailable(conn, owner)) {
                    if (retired >= count) {
                        break;
                    }
                    if (retireCode(conn, owner, code, reason, System.currentTimeMillis()) == 1) {
                        retired++;
                    }
                }
                if (retired > 0) {
                    syncSlots(conn, owner);
                }
                conn.commit();
                return retired;
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("撤销邀请码失败: " + e.getMessage(), e);
        }
    }

    /** Retires every held code (AVAILABLE + RESERVED) and drops the player's pending pre-invites. */
    public int retireAllHeld(UUID owner, String reason) {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                lockPlayer(conn, owner);
                int retired;
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("invite_codes")
                        + " SET status = ?, reserved_target = NULL, reserved_at = NULL, retired_reason = ?"
                        + " WHERE owner_uuid = ? AND status IN (?, ?)")) {
                    stmt.setString(1, STATUS_RETIRED);
                    stmt.setString(2, reason);
                    stmt.setString(3, owner.toString());
                    stmt.setString(4, STATUS_AVAILABLE);
                    stmt.setString(5, STATUS_RESERVED);
                    retired = stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM " + t("pending_invites") + " WHERE inviter_uuid = ?")) {
                    stmt.setString(1, owner.toString());
                    stmt.executeUpdate();
                }
                syncSlots(conn, owner);
                conn.commit();
                return retired;
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("撤销全部邀请码失败: " + e.getMessage(), e);
        }
    }

    /** Clears the quota watermark so a reset player restarts from the next boundary. */
    public void resetWatermark(UUID owner) {
        try (Connection conn = connections.open();
             PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("players")
                 + " SET quota_policy_key = NULL, quota_next_boundary = -1,"
                 + " quota_high_water_minutes = 0, quota_legacy_migrated = 0 WHERE uuid = ?")) {
            stmt.setString(1, owner.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("重置配额水位失败: " + e.getMessage(), e);
        }
    }

    // ───────────────────────── internals ─────────────────────────

    private void prepare(Connection conn) throws SQLException {
        if (!mysql) {
            try (Statement busy = conn.createStatement()) {
                busy.execute("PRAGMA busy_timeout=8000");
            }
        }
    }

    private String forUpdate() {
        return mysql ? " FOR UPDATE" : "";
    }

    private String insertIgnore() {
        return mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ";
    }

    private PlayerRow lockPlayer(Connection conn, UUID uuid) throws SQLException {
        String sql = "SELECT total_invites, invite_slots, quota_policy_key, quota_next_boundary,"
            + " quota_high_water_minutes, quota_legacy_migrated, invite_code, personal_code_ready,"
            + " quota_periods_granted, bound_invite_code FROM " + t("players")
            + " WHERE uuid = ?" + forUpdate();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PlayerRow(
                    rs.getInt("total_invites"),
                    Math.max(0, rs.getInt("invite_slots")),
                    rs.getString("quota_policy_key"),
                    rs.getInt("quota_next_boundary"),
                    Math.max(0L, rs.getLong("quota_high_water_minutes")),
                    rs.getInt("quota_legacy_migrated") == 1,
                    rs.getString("invite_code"),
                    rs.getBoolean("personal_code_ready"),
                    rs.getInt("quota_periods_granted"),
                    rs.getString("bound_invite_code"));
            }
        }
    }

    /**
     * A player counts as registered when they already bound a code, claimed a hidden registration
     * slot, or were migrated. Everything else is still behind the binding gate and must not farm
     * invitation codes.
     */
    private boolean isRegistered(Connection conn, UUID uuid, PlayerRow row) throws SQLException {
        if (row.personalCodeReady) {
            return true;
        }
        if (exists(conn, "SELECT 1 FROM " + t("records") + " WHERE invitee_uuid = ?", uuid.toString())) {
            return true;
        }
        return hasRegistrationClaim(conn, uuid);
    }

    private boolean hasRegistrationClaim(Connection conn, UUID uuid) throws SQLException {
        return exists(conn, "SELECT 1 FROM " + t("hidden_registration_codes") + " WHERE claimed_uuid = ?",
            uuid.toString());
    }

    private Boolean inviteePersonalCodeReady(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT personal_code_ready FROM " + t("players") + " WHERE uuid = ?" + forUpdate())) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    private int consume(Connection conn, String code, String expectedStatus, UUID invitee, long now)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t("invite_codes")
                + " SET status = ?, used_at = ?, used_by_uuid = ? WHERE code = ? AND status = ?")) {
            stmt.setString(1, STATUS_USED);
            stmt.setLong(2, now);
            stmt.setString(3, invitee.toString());
            stmt.setString(4, code);
            stmt.setString(5, expectedStatus);
            return stmt.executeUpdate();
        }
    }

    private int retireCode(Connection conn, UUID owner, String code, String reason, long now)
        throws SQLException {
        if (!"LEGACY".equals(reason)) {
            try (PreparedStatement update = conn.prepareStatement("UPDATE " + t("invite_codes")
                    + " SET status = ?, retired_reason = ? WHERE code = ? AND owner_uuid = ? AND status = ?")) {
                update.setString(1, STATUS_RETIRED);
                update.setString(2, reason);
                update.setString(3, code);
                update.setString(4, owner.toString());
                update.setString(5, STATUS_AVAILABLE);
                return update.executeUpdate();
            }
        }
        try (PreparedStatement stmt = conn.prepareStatement(insertIgnore() + t("invite_codes")
                + " (code, owner_uuid, status, boundary_index, legacy, issued_at, retired_reason)"
                + " VALUES (?, ?, ?, 0, 1, ?, ?)")) {
            stmt.setString(1, code);
            stmt.setString(2, owner.toString());
            stmt.setString(3, STATUS_RETIRED);
            stmt.setLong(4, now);
            stmt.setString(5, reason);
            return stmt.executeUpdate();
        }
    }

    /** Recomputes {@code players.invite_slots} from the ledger; the column stays for compatibility. */
    public int syncSlots(Connection conn, UUID owner) throws SQLException {
        int available = countAvailable(conn, owner);
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE " + t("players") + " SET invite_slots = ? WHERE uuid = ?")) {
            stmt.setInt(1, available);
            stmt.setString(2, owner.toString());
            stmt.executeUpdate();
        }
        return available;
    }

    private void bumpIpClaim(Connection conn, String ip, long now) throws SQLException {
        String sql = mysql
            ? "INSERT INTO " + t("ip_claims") + " (ip, bind_count, last_bound_at) VALUES (?, 1, ?)"
                + " ON DUPLICATE KEY UPDATE bind_count = bind_count + 1, last_bound_at = VALUES(last_bound_at)"
            : "INSERT INTO " + t("ip_claims") + " (ip, bind_count, last_bound_at) VALUES (?, 1, ?)"
                + " ON CONFLICT(ip) DO UPDATE SET bind_count = bind_count + 1,"
                + " last_bound_at = excluded.last_bound_at";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, ip);
            stmt.setLong(2, now);
            stmt.executeUpdate();
        }
    }

    private void upsertBoundPlayerName(Connection conn, String name, UUID invitee,
                                       UUID inviter, long now) throws SQLException {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        String sql = mysql
            ? "INSERT INTO " + t("bound_player_names")
                + " (name_lower, player_uuid, display_name, inviter_uuid, bound_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid),"
                + " display_name = VALUES(display_name), inviter_uuid = VALUES(inviter_uuid),"
                + " updated_at = VALUES(updated_at)"
            : "INSERT INTO " + t("bound_player_names")
                + " (name_lower, player_uuid, display_name, inviter_uuid, bound_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(name_lower) DO UPDATE SET"
                + " player_uuid = excluded.player_uuid, display_name = excluded.display_name,"
                + " inviter_uuid = excluded.inviter_uuid, updated_at = excluded.updated_at";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setString(2, invitee.toString());
            stmt.setString(3, name);
            stmt.setString(4, inviter.toString());
            stmt.setLong(5, now);
            stmt.setLong(6, now);
            stmt.executeUpdate();
        }
    }

    private boolean exists(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int count(Connection conn, String sql, String... params) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setString(i + 1, params[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static boolean isConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return (state != null && state.startsWith("23"))
            || message.contains("unique")
            || message.contains("duplicate")
            || message.contains("constraint failed");
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }
}
