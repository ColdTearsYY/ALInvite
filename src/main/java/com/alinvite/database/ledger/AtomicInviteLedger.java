package com.alinvite.database.ledger;

import com.alinvite.manager.QuotaRule;
import com.alinvite.manager.QuotaRuleSet;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Owns a JDBC unit of work across quota settlement, the existing ledger and delivery enqueue. */
public final class AtomicInviteLedger {
    @FunctionalInterface public interface BindHook {
        void bound(Connection conn, InviteLedgerDao.BindRequest request, InviteLedgerDao.BindOutcome outcome) throws SQLException;
    }
    public record Pending(String targetName, String displayName, UUID inviterUuid, String inviterIp,
                          String reservedCode, String reservationId, long createdAt, Long expiresAt) {}
    public record Inventory(String policyKey, int maxCodes, int nextBoundary, long totalMillis,
                            int available, int reserved, boolean registered) {}
    private final InviteLedgerDao.ConnectionProvider connections;
    private final String prefix;
    private final boolean mysql;
    private final InviteLedgerDao.CodeFactory codes;
    private final LongSupplier clock;
    private volatile Function<String, QuotaRule> rules = key -> null;
    private volatile BindHook hook = (conn, request, outcome) -> {};
    private volatile boolean enabled = true;

    public AtomicInviteLedger(InviteLedgerDao.ConnectionProvider connections, String prefix, boolean mysql,
                              InviteLedgerDao.CodeFactory codes) {
        this(connections, prefix, mysql, codes, null);
    }
    public AtomicInviteLedger(InviteLedgerDao.ConnectionProvider connections, String prefix, boolean mysql,
                              InviteLedgerDao.CodeFactory codes, LongSupplier clock) {
        this.connections = connections; this.prefix = prefix; this.mysql = mysql; this.codes = codes; this.clock = clock;
    }
    public void configure(QuotaRuleSet rules, boolean enabled) { this.rules = rules::byKey; this.enabled = enabled; }
    public void setBindHook(BindHook hook) { this.hook = hook; }
    private String t(String table) { return prefix + table; }
    private String lockSuffix() { return mysql ? " FOR UPDATE" : ""; }

    public void ensureSchema() throws SQLException {
        addColumn("players", "quota_max_codes", "INT NOT NULL DEFAULT -1");
        addColumn("pending_invites", "expires_at", "BIGINT");
        addColumn("pending_invites", "reservation_id", "VARCHAR(36)");
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
            List<String> targets = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("SELECT target_name FROM " + t("pending_invites")
                    + " WHERE reservation_id IS NULL")) {
                while (rs.next()) targets.add(rs.getString(1));
            }
            for (String target : targets) try (PreparedStatement ps = conn.prepareStatement("UPDATE "
                    + t("pending_invites") + " SET reservation_id = ? WHERE target_name = ? AND reservation_id IS NULL")) {
                ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, target); ps.executeUpdate();
            }
        }
    }
    private void addColumn(String table, String column, String definition) throws SQLException {
        try (Connection conn = connections.open()) {
            try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, t(table), column)) {
                if (rs.next()) return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE " + t(table) + " ADD COLUMN " + column + " " + definition);
            } catch (SQLException error) {
                if (!(mysql && error.getErrorCode() == 1060)) throw error;
            }
        }
    }

    public InviteLedgerDao.TickResult tick(UUID owner, double hours, QuotaRule rule, Function<String, QuotaRule> lookup) {
        return transaction(owner, unit -> {
            Row row = readRow(unit.conn, owner);
            double observed = Math.max(hours, total(unit.conn, owner, row) / 3600000.0);
            Function<String, QuotaRule> previous = key -> {
                QuotaRule found = lookup.apply(key);
                return row != null && key.equals(row.policy) && row.cap >= 0 && found != null
                    ? new QuotaRule(found.group(), found.mode(), found.hoursPerSlot(), row.cap, found.priority()) : found;
            };
            InviteLedgerDao.TickResult result = unit.ledger.applyQuotaTick(owner, observed, rule, previous);
            if (result.status() == InviteLedgerDao.TickStatus.UPDATED) {
                try (PreparedStatement ps = unit.conn.prepareStatement("UPDATE " + t("players") + " SET quota_max_codes = ? WHERE uuid = ?")) {
                    ps.setInt(1, rule.maxCodes()); ps.setString(2, owner.toString()); ps.executeUpdate();
                }
            }
            return result;
        });
    }

    public InviteLedgerDao.BindOutcome bind(InviteLedgerDao.BindRequest request, String reservationId) {
        return bind(request, reservationId, () -> true);
    }

    public InviteLedgerDao.BindOutcome bind(InviteLedgerDao.BindRequest request, String reservationId,
                                            java.util.function.BooleanSupplier admissionPermit) {
        return transaction(request.inviterUuid(), unit -> {
            settle(unit, request.inviterUuid());
            if (request.pendingTargetName() != null) {
                Pending pending = pending(unit.conn, request.pendingTargetName(), true);
                if (pending == null || !pending.inviterUuid().equals(request.inviterUuid())
                        || !java.util.Objects.equals(pending.reservationId(), reservationId)
                        || !java.util.Objects.equals(pending.reservedCode(), request.code())) return unavailable();
                if (expired(pending, now(unit.conn))) {
                    unit.ledger.release(pending.inviterUuid(), pending.targetName());
                    return unavailable();
                }
            }
            if (!admissionPermit.getAsBoolean()) return InviteLedgerDao.BindOutcome.failure(
                InviteLedgerDao.BindStatus.ADMISSION_CLOSED);
            InviteLedgerDao.BindOutcome result = unit.ledger.bind(request);
            if (result.success()) hook.bound(unit.conn, request, result);
            return result;
        });
    }
    private static InviteLedgerDao.BindOutcome unavailable() {
        return new InviteLedgerDao.BindOutcome(InviteLedgerDao.BindStatus.CODE_NOT_AVAILABLE, 0, 0, true, true, true);
    }

    public InviteLedgerDao.ReserveResult reserve(UUID owner, String ip, String target, String display, int maxInvites, long ttlMillis) {
        Pending old = getPending(target);
        if (old != null) expire(old);
        return transaction(owner, unit -> {
            settle(unit, owner);
            InviteLedgerDao.ReserveResult result = unit.ledger.reserve(owner, ip, target, display, maxInvites);
            if (result.status() == InviteLedgerDao.ReserveStatus.SUCCESS) {
                long created = now(unit.conn);
                Long expires = ttlMillis <= 0 ? null : saturatingAdd(created, ttlMillis);
                try (PreparedStatement ps = unit.conn.prepareStatement("UPDATE " + t("pending_invites")
                        + " SET reservation_id = ?, created_at = ?, expires_at = ? WHERE target_name = ? AND inviter_uuid = ? AND reserved_code = ?")) {
                    ps.setString(1, UUID.randomUUID().toString()); ps.setLong(2, created);
                    if (expires == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, expires);
                    ps.setString(4, target); ps.setString(5, owner.toString()); ps.setString(6, result.reservedCode());
                    if (ps.executeUpdate() != 1) throw new SQLException("Reservation snapshot disappeared");
                }
            }
            return result;
        });
    }

    public boolean release(Pending expected) { return release(expected, false); }
    public boolean expire(Pending expected) { return release(expected, true); }
    private boolean release(Pending expected, boolean expiredOnly) {
        return transaction(expected.inviterUuid(), unit -> {
            Pending live = pending(unit.conn, expected.targetName(), true);
            if (live == null || !live.inviterUuid().equals(expected.inviterUuid())
                    || !java.util.Objects.equals(live.reservationId(), expected.reservationId())
                    || !java.util.Objects.equals(live.reservedCode(), expected.reservedCode())) return false;
            if (expiredOnly && !expired(live, now(unit.conn))) return false;
            return unit.ledger.release(live.inviterUuid(), live.targetName());
        });
    }
    public List<UUID> expirePending(int limit) {
        List<Pending> due = new ArrayList<>();
        try (Connection conn = connections.open(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM "
                + t("pending_invites") + " WHERE expires_at IS NOT NULL AND expires_at <= ? ORDER BY expires_at LIMIT ?")) {
            ps.setLong(1, now(conn)); ps.setInt(2, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) due.add(readPending(rs)); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
        List<UUID> owners = new ArrayList<>();
        for (Pending pending : due) if (expire(pending)) owners.add(pending.inviterUuid());
        return owners;
    }
    public List<Pending> listPending(UUID owner, int limit) {
        try (Connection conn = connections.open(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM "
                + t("pending_invites") + (owner == null ? "" : " WHERE inviter_uuid = ?") + " ORDER BY created_at, target_name LIMIT ?")) {
            int index = 1;
            if (owner != null) ps.setString(index++, owner.toString());
            ps.setInt(index, Math.max(1, Math.min(limit, 1000)));
            List<Pending> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(readPending(rs)); }
            return result;
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    public Pending getPending(String target) {
        try (Connection conn = connections.open()) { return pending(conn, target, false); }
        catch (SQLException error) { throw new IllegalStateException(error); }
    }
    private Pending pending(Connection conn, String target, boolean lock) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + t("pending_invites")
                + " WHERE target_name = ?" + (lock ? lockSuffix() : ""))) {
            ps.setString(1, target);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readPending(rs) : null; }
        }
    }
    private Pending readPending(ResultSet rs) throws SQLException {
        long expires = rs.getLong("expires_at");
        Long expiry = rs.wasNull() ? null : expires;
        return new Pending(rs.getString("target_name"), rs.getString("target_display_name"),
            UUID.fromString(rs.getString("inviter_uuid")), rs.getString("inviter_ip"), rs.getString("reserved_code"),
            rs.getString("reservation_id"), rs.getLong("created_at"), expiry);
    }
    private static boolean expired(Pending row, long now) { return row.expiresAt() != null && now >= row.expiresAt(); }
    private static long saturatingAdd(long left, long right) { return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right; }

    public Inventory inventory(UUID owner) {
        return transaction(owner, unit -> {
            Row row = readRow(unit.conn, owner);
            int available = unit.ledger.countAvailable(unit.conn, owner);
            int held = unit.ledger.countHeld(unit.conn, owner);
            return new Inventory(row == null ? null : row.policy, row == null ? -1 : row.cap,
                row == null ? -1 : row.next, total(unit.conn, owner, row), available, held - available, row != null);
        });
    }
    private record Row(String policy, int cap, int next, long minutes) {}
    private Row readRow(Connection conn, UUID owner) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT quota_policy_key, quota_max_codes, quota_next_boundary, quota_high_water_minutes"
                + " FROM " + t("players") + " WHERE uuid = ?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new Row(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)) : null; }
        }
    }
    private long total(Connection conn, UUID owner, Row row) throws SQLException {
        long minutes = row == null ? 0 : Math.max(0, row.minutes);
        long total = minutes > Long.MAX_VALUE / 60000L ? Long.MAX_VALUE : minutes * 60000L;
        try (PreparedStatement ps = conn.prepareStatement("SELECT total_ms FROM " + t("playtime_totals") + " WHERE player_uuid = ?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Math.max(total, rs.getLong(1)) : total; }
        }
    }
    private void settle(Unit unit, UUID owner) throws SQLException {
        if (!enabled) return;
        Row row = readRow(unit.conn, owner);
        if (row == null || row.policy == null || row.policy.isBlank()) return; // Legacy baseline is deferred until an authoritative group observation.
        QuotaRule known = rules.apply(row.policy);
        if (known == null || !row.policy.equals(known.key())) throw new SQLException("Unknown persisted quota policy: " + row.policy);
        if (row.cap >= 0) known = new QuotaRule(known.group(), known.mode(), known.hoursPerSlot(), row.cap, known.priority());
        unit.ledger.applyQuotaTick(owner, total(unit.conn, owner, row) / 3600000.0, known, rules);
    }
    private long now(Connection conn) throws SQLException {
        if (clock != null) return clock.getAsLong();
        String sql = mysql ? "SELECT CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS SIGNED)"
            : "SELECT CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    @FunctionalInterface private interface Work<T> { T apply(Unit unit) throws SQLException; }
    private <T> T transaction(UUID owner, Work<T> operation) {
        for (int attempt = 0; ; attempt++) {
            try (Connection conn = connections.open()) {
                int isolation = conn.getTransactionIsolation();
                if (mysql) conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                else try (Statement stmt = conn.createStatement()) { stmt.execute("PRAGMA busy_timeout=8000"); }
                conn.setAutoCommit(false);
                try {
                    // A write acquires SQLite's writer lock before any reads; on InnoDB it locks the owner row.
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE " + t("players") + " SET uuid = uuid WHERE uuid = ?")) {
                        ps.setString(1, owner.toString()); ps.executeUpdate();
                    }
                    Unit unit = new Unit(conn);
                    T result = operation.apply(unit);
                    if (unit.rollbackOnly) conn.rollback(); else conn.commit();
                    return result;
                } catch (Exception error) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    throw error;
                } finally {
                    conn.setAutoCommit(true);
                    if (mysql) conn.setTransactionIsolation(isolation);
                }
            } catch (Exception error) {
                if (attempt < 4 && contention(error)) {
                    try { Thread.sleep(25L * (attempt + 1)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    continue;
                }
                throw new IllegalStateException("Invitation unit of work failed", error);
            }
        }
    }
    private static boolean contention(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) {
            if (sql.getErrorCode() == 5 || sql.getErrorCode() == 6 || sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205) return true;
        }
        return false;
    }
    private final class Unit {
        final Connection conn;
        final InviteLedgerDao ledger;
        boolean rollbackOnly;
        Unit(Connection conn) {
            this.conn = conn;
            Connection borrowed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close", "commit", "setAutoCommit" -> { return null; }
                        case "rollback" -> { rollbackOnly = true; return null; }
                        case "getAutoCommit" -> { return false; }
                        default -> {
                            try { return method.invoke(conn, args); }
                            catch (InvocationTargetException error) { throw error.getCause(); }
                        }
                    }
                });
            ledger = new InviteLedgerDao(() -> borrowed, prefix, mysql, codes);
        }
    }
}
