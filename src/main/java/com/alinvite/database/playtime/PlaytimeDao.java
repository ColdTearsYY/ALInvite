package com.alinvite.database.playtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Persisted online-time ledger backed by plain JDBC.
 *
 * <p>The ledger answers one question only: how much wall-clock time did this account actually spend
 * online, counted once, no matter how many backends reported it and how often a report was retried.
 * The unit of truth is an <em>interval</em>, not a counter: every report contributes the closed
 * interval {@code [sessionStart, sessionStart + elapsed]} and the stored total is the length of the
 * union of all intervals for that player. That single rule makes duplicate reports, out-of-order
 * retries, a late final quit report arriving after the player already rejoined elsewhere, and the
 * same account being online on two backends all idempotent, because an interval that is already
 * covered adds nothing.
 *
 * <p>Time is measured by the caller with {@link System#nanoTime()} while the player is actually
 * online, so the backend wall clock is never trusted for durations. Two corrections keep the
 * timeline honest across machines:
 * <ul>
 *   <li>{@code elapsed} is frozen when the player quits, so nothing accrues after the fact;</li>
 *   <li>{@code reportAge} is measured by the caller at the moment the DAO really runs, so a report
 *       queued through a shutdown or an offline period is placed back at the time it happened
 *       instead of stretching forward into time the player was not online.</li>
 * </ul>
 * The absolute anchor of a session is {@code databaseNow - reportAge - elapsed}, computed from one
 * common database clock (SQLite and MySQL each have their own expression), so two backends with
 * skewed system clocks still agree on where a session sits on the shared timeline.
 *
 * <p>The class has no Bukkit dependency: callers pass a connection provider, the table prefix and
 * the backend flavour, which keeps the production SQL executable from plain JDBC tests.
 */
public final class PlaytimeDao {

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    /** Table names owned by this DAO; the configured prefix is prepended to each. */
    private static final String TOTALS = "playtime_totals";
    private static final String SESSIONS = "playtime_sessions";
    private static final String INTERVALS = "playtime_intervals";

    /** Legacy invite table holding the quota high-water mark that seeds the first totals row. */
    private static final String PLAYERS = "players";
    private static final String FLOOR_COLUMN = "quota_high_water_minutes";

    /** Only letters, digits and underscore may reach a SQL identifier position. */
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");

    /**
     * Upper bound for one reported span (ten years). It keeps every arithmetic step — anchor,
     * interval end, accumulated total — far away from {@link Long} overflow even if a caller
     * passes a corrupted or hostile value.
     */
    private static final long MAX_SPAN_MILLIS = 10L * 365L * 24L * 60L * 60L * 1000L;
    private static final long MAX_FLOOR_MINUTES = Long.MAX_VALUE / 60_000L;

    /** Bounded SQLite contention retry: a fair queue behind the write lock, never an infinite loop. */
    private static final int MAX_SQLITE_ATTEMPTS = 5;
    private static final long SQLITE_RETRY_BACKOFF_MILLIS = 25L;

    private final ConnectionProvider connections;
    private final String prefix;
    private final boolean mysql;
    /** Null in production: the database itself answers "what time is it" for both backends. */
    private final LongSupplier databaseClock;
    private final LongSupplier monotonicClock;

    public PlaytimeDao(ConnectionProvider connections, String prefix, boolean mysql) {
        this(connections, prefix, mysql, null, System::nanoTime);
    }

    /**
     * Test-only overload: a deterministic database clock. Production code must use the three
     * argument constructor so the common database clock is queried instead of a local one.
     */
    PlaytimeDao(ConnectionProvider connections, String prefix, boolean mysql, LongSupplier databaseClock) {
        this(connections, prefix, mysql, databaseClock, () -> 0L);
    }

    PlaytimeDao(ConnectionProvider connections, String prefix, boolean mysql,
                LongSupplier databaseClock, LongSupplier monotonicClock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.prefix = validatePrefix(prefix);
        this.mysql = mysql;
        this.databaseClock = databaseClock;
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    private static String validatePrefix(String prefix) {
        String candidate = prefix == null ? "" : prefix;
        if (!SAFE_PREFIX.matcher(candidate).matches()) {
            throw new IllegalArgumentException("非法的数据库表前缀: " + candidate);
        }
        return candidate;
    }

    // ───────────────────────── schema ─────────────────────────

    /**
     * Idempotent creation of the three ledger tables. Existing invite ledger tables, quota cursors
     * and historical codes are never touched.
     */
    public void ensureSchema() throws SQLException {
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS %splaytime_totals (
                    player_uuid VARCHAR(36) NOT NULL,
                    total_ms BIGINT NOT NULL DEFAULT 0,
                    seeded INT NOT NULL DEFAULT 0,
                    created_at_ms BIGINT NOT NULL DEFAULT 0,
                    updated_at_ms BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player_uuid)
                )
                """.formatted(prefix) + engineSuffix());
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS %splaytime_sessions (
                    player_uuid VARCHAR(36) NOT NULL,
                    session_id VARCHAR(36) NOT NULL,
                    started_at_ms BIGINT NOT NULL,
                    reported_ms BIGINT NOT NULL DEFAULT 0,
                    last_report_at_ms BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (player_uuid, session_id)
                )
                """.formatted(prefix) + engineSuffix());
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS %splaytime_intervals (
                    player_uuid VARCHAR(36) NOT NULL,
                    start_ms BIGINT NOT NULL,
                    end_ms BIGINT NOT NULL,
                    PRIMARY KEY (player_uuid, start_ms)
                )
                """.formatted(prefix) + engineSuffix());
        }
        try (Connection conn = connections.open()) {
            createIndex(conn, "idx_" + prefix + "playtime_intervals_end",
                INTERVALS, "player_uuid, end_ms");
            createIndex(conn, "idx_" + prefix + "playtime_sessions_player",
                SESSIONS, "player_uuid, last_report_at_ms");
        }
    }

    private String engineSuffix() {
        return mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
    }

    private void createIndex(Connection conn, String indexName, String table, String columns)
        throws SQLException {
        if (mysql) {
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
            } catch (SQLException error) {
                // Another backend can create the same index after our SHOW INDEX check.
                if (error.getErrorCode() != 1061) throw error;
            }
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + t(table) + "(" + columns + ")");
        }
    }

    // ───────────────────────── public API ─────────────────────────

    /**
     * Records one report of an online session and returns the player's new total.
     *
     * @param playerId         account the time belongs to
     * @param sessionId        stable id of one continuous online stretch; the same id must be reused
     *                         by every retry of that stretch, which is what makes retries idempotent
     * @param elapsedMillis    time actually spent online in this stretch, frozen at quit
     * @param reportAgeMillis  delay between the moment the stretch ended and this call, so a report
     *                         that waited through a queue is placed back where it belongs
     * @return the player's total online time in milliseconds after this report
     * @throws SQLException when the transaction failed; nothing is committed in that case
     */
    public long record(UUID playerId, UUID sessionId, long elapsedMillis, long reportAgeMillis)
        throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sessionId, "sessionId");
        long elapsed = clampSpan(elapsedMillis);
        long age = clampSpan(reportAgeMillis);
        long enteredNanos = monotonicClock.getAsLong();

        int attempts = mysql ? 1 : MAX_SQLITE_ATTEMPTS;
        SQLException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return recordOnce(playerId, sessionId, elapsed, age, enteredNanos);
            } catch (SQLException e) {
                last = e;
                if (attempt >= attempts || !isBusy(e)) {
                    throw e;
                }
                sleepQuietly(SQLITE_RETRY_BACKOFF_MILLIS * attempt);
            }
        }
        throw last == null ? new SQLException("记录在线时长失败") : last;
    }

    /**
     * Total online time for one player, creating (and legacy-seeding) the totals row on first use so
     * a player who never reported since the upgrade still sees their imported history.
     */
    public long getTotalMillis(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                long now = databaseNow(conn);
                long total = lockTotals(conn, playerId, now).total();
                conn.commit();
                return total;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    // ───────────────────────── record transaction ─────────────────────────

    private long recordOnce(UUID playerId, UUID sessionId, long elapsed, long age, long enteredNanos) throws SQLException {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                // Preserve the sampled timeline across connection-pool waits and internal retries.
                long waitedMillis = Math.max(0L, monotonicClock.getAsLong() - enteredNanos) / 1_000_000L;
                long effectiveAge = saturatingAdd(age, waitedMillis);
                long now = databaseNow(conn);
                TotalsRow totals = lockTotals(conn, playerId, now);

                SessionRow session = readSession(conn, playerId, sessionId);
                if (session == null) {
                    // First report of this stretch: the anchor is fixed here and never moves again.
                    long start = Math.max(0L, now - effectiveAge - elapsed);
                    insertSession(conn, playerId, sessionId, start, now);
                    session = new SessionRow(start, 0L);
                }

                long total = totals.total();
                if (elapsed > session.reported()) {
                    // Late final reports are accepted, replays and out-of-order retries are not.
                    long end = saturatingAdd(session.start(), elapsed);
                    long covered = mergeIntervals(conn, playerId, session.start(), end);
                    if (covered > 0L) {
                        total = saturatingAdd(total, covered);
                        updateTotals(conn, playerId, total, now);
                    }
                    updateSessionReported(conn, playerId, sessionId, elapsed, now);
                }
                conn.commit();
                return total;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    /**
     * Locks (or creates and seeds) the totals row for one player and returns it.
     *
     * <p>SQLite takes its write lock <em>before</em> the first read: a deferred transaction that
     * reads first and writes later can fail its lock upgrade while another writer holds the lock.
     * MySQL instead locks the row with {@code SELECT ... FOR UPDATE} and inserts on demand.
     */
    private TotalsRow lockTotals(Connection conn, UUID playerId, long now) throws SQLException {
        if (!mysql) {
            // INSERT OR IGNORE is a write, so it acquires the SQLite RESERVED lock immediately.
            try (PreparedStatement stmt = conn.prepareStatement(insertIgnore() + t(TOTALS)
                    + " (player_uuid, total_ms, seeded, created_at_ms, updated_at_ms)"
                    + " VALUES (?, 0, 0, ?, ?)")) {
                stmt.setString(1, playerId.toString());
                stmt.setLong(2, now);
                stmt.setLong(3, now);
                stmt.executeUpdate();
            }
        }
        TotalsRow row = readTotals(conn, playerId);
        if (row == null) {
            try (PreparedStatement stmt = conn.prepareStatement(insertIgnore() + t(TOTALS)
                    + " (player_uuid, total_ms, seeded, created_at_ms, updated_at_ms)"
                    + " VALUES (?, 0, 0, ?, ?)")) {
                stmt.setString(1, playerId.toString());
                stmt.setLong(2, now);
                stmt.setLong(3, now);
                stmt.executeUpdate();
            }
            row = readTotals(conn, playerId);
        }
        if (row == null) {
            throw new SQLException("无法锁定玩家在线时长汇总行: " + playerId);
        }
        if (row.seeded() != 0) {
            return row;
        }
        // Seed once from the invite plugin's quota high-water mark. A later growth of that value is
        // deliberately ignored: the imported floor is history, not a live mirror.
        long floor = quotaFloorMillis(conn, playerId);
        long seededTotal = Math.max(row.total(), floor);
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(TOTALS)
                + " SET total_ms = ?, seeded = 1, updated_at_ms = ? WHERE player_uuid = ?")) {
            stmt.setLong(1, seededTotal);
            stmt.setLong(2, now);
            stmt.setString(3, playerId.toString());
            if (stmt.executeUpdate() != 1) {
                throw new SQLException("初始化玩家在线时长基线失败: " + playerId);
            }
        }
        return new TotalsRow(seededTotal, 1);
    }

    private TotalsRow readTotals(Connection conn, UUID playerId) throws SQLException {
        String sql = "SELECT total_ms, seeded FROM " + t(TOTALS)
            + " WHERE player_uuid = ?" + forUpdate();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TotalsRow(Math.max(0L, rs.getLong(1)), rs.getInt(2));
            }
        }
    }

    /** {@code players.quota_high_water_minutes * 60000}, or zero when no legacy row exists. */
    private long quotaFloorMillis(Connection conn, UUID playerId) throws SQLException {
        String sql = "SELECT " + FLOOR_COLUMN + " FROM " + t(PLAYERS) + " WHERE uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                long minutes = rs.getLong(1);
                if (rs.wasNull() || minutes <= 0L) {
                    return 0L;
                }
                return minutes > MAX_FLOOR_MINUTES ? Long.MAX_VALUE : minutes * 60_000L;
            }
        } catch (SQLException e) {
            // A deployment whose invite tables predate the quota column simply has no floor to
            // import; anything else (locked database, broken connection) must still surface.
            if (isMissingLegacyColumn(e)) {
                return 0L;
            }
            throw e;
        }
    }

    private SessionRow readSession(Connection conn, UUID playerId, UUID sessionId) throws SQLException {
        String sql = "SELECT started_at_ms, reported_ms FROM " + t(SESSIONS)
            + " WHERE player_uuid = ? AND session_id = ?" + forUpdate();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, sessionId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SessionRow(rs.getLong(1), Math.max(0L, rs.getLong(2)));
            }
        }
    }

    private void insertSession(Connection conn, UUID playerId, UUID sessionId, long start, long now)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + t(SESSIONS)
                + " (player_uuid, session_id, started_at_ms, reported_ms, last_report_at_ms)"
                + " VALUES (?, ?, ?, 0, ?)")) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, sessionId.toString());
            stmt.setLong(3, start);
            stmt.setLong(4, now);
            if (stmt.executeUpdate() != 1) {
                throw new SQLException("创建在线会话失败: " + sessionId);
            }
        }
    }

    /** Monotonic: a smaller late value can never shrink what a session already reported. */
    private void updateSessionReported(Connection conn, UUID playerId, UUID sessionId, long elapsed,
                                       long now) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(SESSIONS)
                + " SET reported_ms = ?, last_report_at_ms = ?"
                + " WHERE player_uuid = ? AND session_id = ? AND reported_ms < ?")) {
            stmt.setLong(1, elapsed);
            stmt.setLong(2, now);
            stmt.setString(3, playerId.toString());
            stmt.setString(4, sessionId.toString());
            stmt.setLong(5, elapsed);
            stmt.executeUpdate();
        }
    }

    private void updateTotals(Connection conn, UUID playerId, long total, long now) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(TOTALS)
                + " SET total_ms = ?, updated_at_ms = ? WHERE player_uuid = ?")) {
            stmt.setLong(1, total);
            stmt.setLong(2, now);
            stmt.setString(3, playerId.toString());
            if (stmt.executeUpdate() != 1) {
                throw new SQLException("更新玩家在线时长失败: " + playerId);
            }
        }
    }

    /**
     * Adds {@code [startMs, endMs)} to the player's interval set and returns how many milliseconds
     * of it were not covered before. Overlapping and merely touching intervals are folded into one
     * row, so the same minute can never be counted twice and the table stays small.
     */
    private long mergeIntervals(Connection conn, UUID playerId, long startMs, long endMs)
        throws SQLException {
        if (endMs <= startMs) {
            return 0L;
        }
        List<long[]> selected = new ArrayList<>();
        long mergedStart = startMs;
        long mergedEnd = endMs;
        long alreadyCovered = 0L;
        // Touching counts as overlap: end_ms == start_ms would otherwise leave a zero-length seam
        // that a later report could split into two rows.
        String sql = "SELECT start_ms, end_ms FROM " + t(INTERVALS)
            + " WHERE player_uuid = ? AND end_ms >= ? AND start_ms <= ?" + forUpdate();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setLong(2, startMs);
            stmt.setLong(3, endMs);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long s = rs.getLong(1);
                    long e = rs.getLong(2);
                    selected.add(new long[]{s, e});
                    mergedStart = Math.min(mergedStart, s);
                    mergedEnd = Math.max(mergedEnd, e);
                    alreadyCovered += Math.max(0L, e - s);
                }
            }
        }
        if (selected.isEmpty()) {
            insertInterval(conn, playerId, startMs, endMs);
            return endMs - startMs;
        }
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + t(INTERVALS)
                + " WHERE player_uuid = ? AND end_ms >= ? AND start_ms <= ?")) {
            stmt.setString(1, playerId.toString());
            stmt.setLong(2, startMs);
            stmt.setLong(3, endMs);
            stmt.executeUpdate();
        }
        insertInterval(conn, playerId, mergedStart, mergedEnd);
        return Math.max(0L, (mergedEnd - mergedStart) - alreadyCovered);
    }

    private void insertInterval(Connection conn, UUID playerId, long startMs, long endMs)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + t(INTERVALS)
                + " (player_uuid, start_ms, end_ms) VALUES (?, ?, ?)")) {
            stmt.setString(1, playerId.toString());
            stmt.setLong(2, startMs);
            stmt.setLong(3, endMs);
            if (stmt.executeUpdate() != 1) {
                throw new SQLException("写入在线区间失败: " + playerId);
            }
        }
    }

    // ───────────────────────── internals ─────────────────────────

    private String t(String name) {
        return prefix + name;
    }

    private String forUpdate() {
        return mysql ? " FOR UPDATE" : "";
    }

    private String insertIgnore() {
        return mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ";
    }

    private void prepare(Connection conn) throws SQLException {
        if (!mysql) {
            try (Statement busy = conn.createStatement()) {
                busy.execute("PRAGMA busy_timeout=8000");
            }
        }
    }

    /**
     * The one shared clock. SQLite and MySQL spell "now in epoch milliseconds" differently, and
     * neither reads the JVM clock, so a skewed backend cannot drag a session onto the wrong place
     * of the shared timeline.
     */
    private long databaseNow(Connection conn) throws SQLException {
        if (databaseClock != null) {
            return databaseClock.getAsLong();
        }
        String sql = mysql
            ? "SELECT CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS SIGNED)"
            : "SELECT CAST((julianday('now') - 2440587.5) * 86400000.0 AS INTEGER)";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("数据库未能返回当前时间");
            }
            return rs.getLong(1);
        }
    }

    private static long clampSpan(long value) {
        if (value <= 0L) {
            return 0L;
        }
        return Math.min(value, MAX_SPAN_MILLIS);
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0L) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    private static boolean isBusy(SQLException e) {
        int code = e.getErrorCode();
        if (code == 5 || code == 6) {
            return true;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("database is locked") || message.contains("database table is locked")
            || message.contains("sqlite_busy");
    }

    private static boolean isMissingLegacyColumn(SQLException e) {
        String state = e.getSQLState();
        if ("42S02".equals(state) || "42S22".equals(state)) {
            return true;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("no such table") || message.contains("no such column")
            || message.contains("unknown column") || message.contains("doesn't exist");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Connection is already unusable; closing it is the caller's job.
        }
    }

    private record TotalsRow(long total, int seeded) {
    }

    private record SessionRow(long start, long reported) {
    }
}
