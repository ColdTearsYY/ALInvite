package com.alinvite.database.delivery;

import java.security.SecureRandom;
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
 * Durable delivery outbox: the database, not memory, is the only record of a side effect that still
 * has to happen.
 *
 * <p>Every reward, whitelist call or command that must survive a crash is a row in
 * {@code <prefix>delivery_outbox}. A row walks one linear path
 * {@code READY -> CLAIMED -> RUNNING -> DONE}; two side branches exist on purpose:
 * {@code READY} (retry later, effect known not to have happened) and {@code REVIEW} (effect
 * unknown, a human must decide). The whole design rests on one rule: <em>a state change only
 * happens when the caller still owns the row</em>. Ownership is a random claim token plus a lease
 * deadline, and every mutation is a conditional {@code UPDATE ... WHERE state = ? AND claim_token
 * = ?} whose affected-row count decides the winner. That is what makes several threads, several
 * servers sharing one MySQL database, and a crashed worker that wakes up much later all safe: at
 * most one worker can be inside {@code RUNNING} with a live lease, and a stale worker's token stops
 * matching the moment anyone recovers its lease.
 *
 * <p>Recovery is deliberately conservative. An expired {@code CLAIMED} row never began its effect,
 * so it is safe to retry. An expired {@code RUNNING} row may have been killed in the middle of a
 * reward, so it is retried only when the caller declared the operation idempotent
 * ({@link WorkSpec#retrySafe()}); everything else becomes {@code REVIEW} and waits for an
 * administrator instead of silently paying twice. {@code DONE} is terminal: nothing in this class
 * ever moves a row out of it.
 *
 * <p>Every transition is also appended to {@code <prefix>delivery_audit} in the same transaction,
 * so the history of one row — who retried it, who acknowledged it and why — survives restarts and
 * is readable with {@link #history(String, int)}.
 *
 * <p>The class has no Bukkit dependency: callers pass a connection provider, the table prefix and
 * the backend flavour, which keeps the production SQL executable from plain JDBC tests.
 */
public final class DeliveryDao {

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    /** Waiting to be claimed; {@code next_attempt_at_ms} says when it becomes eligible. */
    public static final String STATE_READY = "READY";
    /** Owned by a worker that has not started the side effect yet. */
    public static final String STATE_CLAIMED = "CLAIMED";
    /** The side effect is being performed right now; only a live lease may still finish it. */
    public static final String STATE_RUNNING = "RUNNING";
    /** Delivered. Terminal: no path in this class leaves this state. */
    public static final String STATE_DONE = "DONE";
    /** Ambiguous outcome. Only an administrator can resolve it. */
    public static final String STATE_REVIEW = "REVIEW";

    /** Table names owned by this DAO; the configured prefix is prepended to each. */
    private static final String OUTBOX = "delivery_outbox";
    private static final String AUDIT = "delivery_audit";

    /** Column list shared by every read, so a row is never half-mapped. */
    private static final String COLUMNS = "id, recipient_uuid, kind, payload, retry_safe, state,"
        + " attempts, claim_token, lease_until_ms, next_attempt_at_ms, last_error, created_at_ms,"
        + " updated_at_ms";

    /** Only letters, digits and underscore may reach a SQL identifier position. */
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");

    /** Deterministic job ids are {@code kind:uuid:index}; the column allows far more. */
    private static final int MAX_ID_LENGTH = 160;
    private static final int MAX_WORKER_LENGTH = 64;
    private static final int MAX_ACTOR_LENGTH = 64;
    private static final int MAX_TEXT_LENGTH = 1000;
    private static final int MAX_LIMIT = 1000;

    /** A lease longer than a day is a configuration mistake, not a plan. */
    private static final long MAX_LEASE_MILLIS = 24L * 60L * 60L * 1000L;

    /** Bounded SQLite contention retry: a fair queue behind the write lock, never an infinite loop. */
    private static final int MAX_SQLITE_ATTEMPTS = 5;
    private static final int MAX_MYSQL_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 25L;

    private static final SecureRandom TOKENS = new SecureRandom();

    private final ConnectionProvider connections;
    private final String prefix;
    private final boolean mysql;
    /** Null in production: the database itself answers "what time is it" for both backends. */
    private final LongSupplier databaseClock;

    public DeliveryDao(ConnectionProvider connections, String prefix, boolean mysql) {
        this(connections, prefix, mysql, null);
    }

    /**
     * Test-only overload: a deterministic database clock. Production code must use the three
     * argument constructor so lease deadlines come from the shared database clock instead of a
     * local one.
     */
    DeliveryDao(ConnectionProvider connections, String prefix, boolean mysql, LongSupplier databaseClock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.prefix = validatePrefix(prefix);
        this.mysql = mysql;
        this.databaseClock = databaseClock;
    }

    // ───────────────────────── value types ─────────────────────────

    /**
     * One durable side effect as the caller describes it.
     *
     * <p>{@code id} is deterministic ({@code bind:<uuid>:whitelist}, {@code milestone:<uuid>:3}) so
     * a retried enqueue deduplicates against the row already stored instead of paying twice.
     * {@code payload} is opaque JSON owned by the caller and never interpreted here.
     * {@code retrySafe} is the caller's promise that running the effect twice is harmless; it is
     * the only thing that allows an expired {@code RUNNING} row to be retried automatically.
     */
    public record WorkSpec(String id, UUID recipientUuid, String kind, String payload, boolean retrySafe) {

        public WorkSpec {
            id = validateId(id);
            Objects.requireNonNull(recipientUuid, "recipientUuid");
            kind = kind == null || kind.isBlank() ? "generic" : clip(kind.trim(), 64);
            payload = payload == null ? "" : payload;
        }
    }

    /** One outbox row: the immutable job description plus its live delivery state. */
    public record Work(String id, UUID recipientUuid, String kind, String payload, boolean retrySafe,
                       String state, int attempts, String claimToken, long leaseUntil,
                       long nextAttemptAt, String lastError, long createdAt, long updatedAt) {
    }

    /** One audited transition of one row. */
    public record AuditEntry(long sequence, String workId, String fromState, String toState,
                             String actor, String reason, long atMillis) {
    }

    /**
     * Idempotent creation of the outbox and its audit trail. Nothing else in the plugin is touched:
     * the tables are additive, so an upgrade can run this on every start.
     */
    public void ensureSchema() throws SQLException {
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS %sdelivery_outbox (
                    id VARCHAR(160) NOT NULL,
                    recipient_uuid VARCHAR(36) NOT NULL,
                    kind VARCHAR(64) NOT NULL,
                    payload MEDIUMTEXT NOT NULL,
                    retry_safe INT NOT NULL DEFAULT 0,
                    state VARCHAR(16) NOT NULL,
                    attempts INT NOT NULL DEFAULT 0,
                    claim_token VARCHAR(64),
                    worker_id VARCHAR(64),
                    lease_until_ms BIGINT NOT NULL DEFAULT 0,
                    next_attempt_at_ms BIGINT NOT NULL DEFAULT 0,
                    last_error TEXT,
                    created_at_ms BIGINT NOT NULL,
                    updated_at_ms BIGINT NOT NULL,
                    PRIMARY KEY (id)
                )
                """.formatted(prefix) + engineSuffix());
            if (mysql) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sdelivery_audit (
                        seq BIGINT NOT NULL AUTO_INCREMENT,
                        work_id VARCHAR(160) NOT NULL,
                        from_state VARCHAR(16),
                        to_state VARCHAR(16) NOT NULL,
                        actor VARCHAR(64),
                        reason TEXT,
                        at_ms BIGINT NOT NULL,
                        PRIMARY KEY (seq)
                    )
                    """.formatted(prefix) + engineSuffix());
            } else {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %sdelivery_audit (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        work_id VARCHAR(160) NOT NULL,
                        from_state VARCHAR(16),
                        to_state VARCHAR(16) NOT NULL,
                        actor VARCHAR(64),
                        reason TEXT,
                        at_ms BIGINT NOT NULL
                    )
                    """.formatted(prefix));
            }
        }
        try (Connection conn = connections.open()) {
            createIndex(conn, "idx_" + prefix + "delivery_outbox_ready", OUTBOX,
                "state, next_attempt_at_ms");
            createIndex(conn, "idx_" + prefix + "delivery_outbox_recipient", OUTBOX,
                "recipient_uuid, created_at_ms");
            createIndex(conn, "idx_" + prefix + "delivery_audit_work", AUDIT, "work_id, seq");
        }
    }

    private String engineSuffix() {
        return mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
    }

    /**
     * Creates one index, tolerating only the MySQL "duplicate key name" error (1061) that a second
     * backend starting at the same moment produces. Any other SQL error is a real problem and is
     * rethrown.
     */
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

    // ───────────────────────── enqueue ─────────────────────────

    /**
     * Convenience enqueue in its own transaction, for callers that are not already inside one.
     *
     * @return the number of rows actually inserted (duplicates are counted as zero)
     */
    public int enqueue(List<WorkSpec> specs) throws SQLException {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            return insert(conn, specs, now);
        });
    }

    /**
     * Writes the given jobs <em>inside the caller's transaction</em> and never commits: the reward
     * row and the business change that earned it either both exist or neither does.
     *
     * <p>A job id that is already stored is left exactly as it is — payload, state, attempts and
     * token are never overwritten by a second enqueue, so a replayed bind cannot resurrect a
     * delivered reward or reset a running one. The caller's transaction owns the SQLite write lock
     * from its own first write; this method adds no extra lock step and performs no commit.
     *
     * @return the number of rows actually inserted
     */
    public int enqueue(Connection conn, List<WorkSpec> specs) throws SQLException {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        return insert(conn, specs, databaseNow(conn));
    }

    private int insert(Connection conn, List<WorkSpec> specs, long now) throws SQLException {
        int inserted = 0;
        try (PreparedStatement stmt = conn.prepareStatement(insertIgnore() + t(OUTBOX)
                + " (id, recipient_uuid, kind, payload, retry_safe, state, attempts, claim_token,"
                + " worker_id, lease_until_ms, next_attempt_at_ms, last_error, created_at_ms, updated_at_ms)"
                + " VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL, 0, 0, NULL, ?, ?)")) {
            for (WorkSpec spec : specs) {
                if (spec == null) {
                    continue;
                }
                stmt.setString(1, spec.id());
                stmt.setString(2, spec.recipientUuid().toString());
                stmt.setString(3, spec.kind());
                stmt.setString(4, spec.payload());
                stmt.setInt(5, spec.retrySafe() ? 1 : 0);
                stmt.setString(6, STATE_READY);
                stmt.setLong(7, now);
                stmt.setLong(8, now);
                inserted += stmt.executeUpdate();
            }
        }
        return inserted;
    }

    // ───────────────────────── reads ─────────────────────────

    /**
     * Rows for one recipient (or every recipient when {@code recipientOrNull} is null), newest
     * first. Intended for administration; the delivery loop uses {@link #ready(UUID, int)}.
     */
    public List<Work> list(UUID recipientOrNull, int limit) throws SQLException {
        int bounded = clampLimit(limit);
        if (bounded <= 0) {
            return List.of();
        }
        try (Connection conn = connections.open()) {
            prepare(conn);
            String sql = "SELECT " + COLUMNS + " FROM " + t(OUTBOX)
                + (recipientOrNull == null ? "" : " WHERE recipient_uuid = ?")
                + " ORDER BY created_at_ms DESC, id DESC LIMIT " + bounded;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (recipientOrNull != null) {
                    stmt.setString(1, recipientOrNull.toString());
                }
                return readAll(stmt);
            }
        }
    }

    /** One row by id, or null when it was never enqueued. */
    public Work find(String id) throws SQLException {
        String workId = validateId(id);
        try (Connection conn = connections.open()) {
            prepare(conn);
            return readById(conn, workId);
        }
    }

    /**
     * Only rows that may be claimed right now: {@code READY} and past their retry delay, oldest
     * deadline first. {@code recipientOrNull == null} means "any recipient" — used by the global
     * sweeper for work that can finish while the player is offline (whitelist registration).
     */
    public List<Work> ready(UUID recipientOrNull, int limit) throws SQLException {
        int bounded = clampLimit(limit);
        if (bounded <= 0) {
            return List.of();
        }
        try (Connection conn = connections.open()) {
            prepare(conn);
            long now = databaseNow(conn);
            String sql = "SELECT " + COLUMNS + " FROM " + t(OUTBOX)
                + " WHERE state = ? AND next_attempt_at_ms <= ?"
                + (recipientOrNull == null ? "" : " AND recipient_uuid = ?")
                + " ORDER BY next_attempt_at_ms, created_at_ms, id LIMIT " + bounded;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, STATE_READY);
                stmt.setLong(2, now);
                if (recipientOrNull != null) {
                    stmt.setString(3, recipientOrNull.toString());
                }
                return readAll(stmt);
            }
        }
    }

    /** Audited transitions of one row, newest first. */
    public List<AuditEntry> history(String id, int limit) throws SQLException {
        String workId = validateId(id);
        int bounded = clampLimit(limit);
        if (bounded <= 0) {
            return List.of();
        }
        try (Connection conn = connections.open()) {
            prepare(conn);
            String sql = "SELECT seq, work_id, from_state, to_state, actor, reason, at_ms FROM "
                + t(AUDIT) + " WHERE work_id = ? ORDER BY seq DESC LIMIT " + bounded;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, workId);
                List<AuditEntry> result = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)));
                    }
                }
                return result;
            }
        }
    }

    // ───────────────────────── claim lifecycle ─────────────────────────

    /**
     * Takes ownership of one eligible {@code READY} row.
     *
     * <p>The claim is a single conditional update, so of any number of competing workers exactly
     * one sees an affected row and gets a {@link Work}; the losers get null. The token is freshly
     * random per claim, which is what invalidates every earlier owner at once.
     *
     * @return the claimed row with its new token, or null when the row was not claimable
     */
    public Work claim(String id, String workerId, long leaseMillis) throws SQLException {
        String workId = validateId(id);
        String worker = workerId == null || workerId.isBlank()
            ? "worker" : clip(workerId.trim(), MAX_WORKER_LENGTH);
        long lease = clampLease(leaseMillis);
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            String token = newToken();
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                    + " SET state = ?, claim_token = ?, worker_id = ?, lease_until_ms = ?,"
                    + " attempts = attempts + 1, updated_at_ms = ?"
                    + " WHERE id = ? AND state = ? AND next_attempt_at_ms <= ?")) {
                stmt.setString(1, STATE_CLAIMED);
                stmt.setString(2, token);
                stmt.setString(3, worker);
                stmt.setLong(4, saturatingAdd(now, lease));
                stmt.setLong(5, now);
                stmt.setString(6, workId);
                stmt.setString(7, STATE_READY);
                stmt.setLong(8, now);
                if (stmt.executeUpdate() != 1) {
                    return null;
                }
            }
            audit(conn, workId, STATE_READY, STATE_CLAIMED, worker, "claimed", now);
            return readById(conn, workId);
        });
    }

    /**
     * Enters {@code RUNNING} and renews the lease — the last moment at which the side effect may
     * still be cancelled. Fails when the lease already expired, which is exactly how a worker that
     * was paused across its deadline is stopped before it touches anything.
     *
     * @return true when this worker still owned an unexpired claim
     */
    public boolean begin(String id, String token, long leaseMillis) throws SQLException {
        String workId = validateId(id);
        String claimToken = validateToken(token);
        long lease = clampLease(leaseMillis);
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                    + " SET state = ?, lease_until_ms = ?, updated_at_ms = ?"
                    + " WHERE id = ? AND state = ? AND claim_token = ? AND lease_until_ms > ?")) {
                stmt.setString(1, STATE_RUNNING);
                stmt.setLong(2, saturatingAdd(now, lease));
                stmt.setLong(3, now);
                stmt.setString(4, workId);
                stmt.setString(5, STATE_CLAIMED);
                stmt.setString(6, claimToken);
                stmt.setLong(7, now);
                if (stmt.executeUpdate() != 1) {
                    return false;
                }
            }
            audit(conn, workId, STATE_CLAIMED, STATE_RUNNING, workerOf(conn, workId),
                "started", now);
            return true;
        });
    }

    /**
     * Records a confirmed delivery. {@code DONE} is terminal and is never replayed, so this is the
     * only way into it and nothing here moves a row back out.
     *
     * @return true when this call performed the transition
     */
    public boolean finish(String id, String token) throws SQLException {
        String workId = validateId(id);
        String claimToken = validateToken(token);
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            String worker = workerOf(conn, workId);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                    + " SET state = ?, lease_until_ms = 0, next_attempt_at_ms = 0, last_error = NULL,"
                    + " updated_at_ms = ? WHERE id = ? AND state = ? AND claim_token = ?")) {
                stmt.setString(1, STATE_DONE);
                stmt.setLong(2, now);
                stmt.setString(3, workId);
                stmt.setString(4, STATE_RUNNING);
                stmt.setString(5, claimToken);
                if (stmt.executeUpdate() != 1) {
                    return false;
                }
            }
            audit(conn, workId, STATE_RUNNING, STATE_DONE, worker, "confirmed", now);
            return true;
        });
    }

    /** Expand a milestone into individual rewards and acknowledge its fenced parent atomically. */
    public boolean finishAndEnqueue(String id, String token, List<WorkSpec> children) throws SQLException {
        String workId = validateId(id), claimToken = validateToken(token);
        return inTransaction(conn -> {
            Work parent = readById(conn, workId);
            if (parent == null || !STATE_RUNNING.equals(parent.state())
                    || !claimToken.equals(parent.claimToken())) return false;
            long now = databaseNow(conn);
            if (parent.leaseUntil() <= now) return false;
            enqueue(conn, children);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                    + " SET state = ?, lease_until_ms = 0, updated_at_ms = ?, last_error = NULL WHERE id = ? AND claim_token = ? AND state = ?")) {
                stmt.setString(1, STATE_DONE); stmt.setLong(2, now); stmt.setString(3, workId);
                stmt.setString(4, claimToken); stmt.setString(5, STATE_RUNNING);
                if (stmt.executeUpdate() != 1) throw new SQLException("Delivery parent fence changed");
            }
            audit(conn, workId, STATE_RUNNING, STATE_DONE, workerOf(conn, workId), "reward items queued", now);
            return true;
        });
    }

    public List<Work> readyKind(String kind, int limit) throws SQLException {
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + t(OUTBOX)
                + " WHERE state = ? AND kind = ? AND next_attempt_at_ms <= ? ORDER BY created_at_ms, id LIMIT " + clampLimit(limit))) {
            stmt.setString(1, STATE_READY); stmt.setString(2, kind); stmt.setLong(3, databaseNow(conn));
            return readAll(stmt);
        }
    }

    /**
     * Returns a row to {@code READY} for a failure that is known to have happened <em>before</em>
     * the side effect: recipient offline, inventory full, provider unavailable. The token is
     * dropped, so the next attempt is a genuinely new one.
     *
     * @param delayMillis how long to wait before the row becomes eligible again
     * @return true when this call performed the transition
     */
    public boolean defer(String id, String token, String reason, long delayMillis) throws SQLException {
        String workId = validateId(id);
        String claimToken = validateToken(token);
        long delay = Math.max(0L, Math.min(delayMillis, MAX_LEASE_MILLIS));
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            String worker = workerOf(conn, workId);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                    + " SET state = ?, claim_token = NULL, lease_until_ms = 0,"
                    + " next_attempt_at_ms = ?, last_error = ?, updated_at_ms = ?"
                    + " WHERE id = ? AND state IN (?, ?) AND claim_token = ?")) {
                stmt.setString(1, STATE_READY);
                stmt.setLong(2, saturatingAdd(now, delay));
                stmt.setString(3, clip(reason, MAX_TEXT_LENGTH));
                stmt.setLong(4, now);
                stmt.setString(5, workId);
                stmt.setString(6, STATE_CLAIMED);
                stmt.setString(7, STATE_RUNNING);
                stmt.setString(8, claimToken);
                if (stmt.executeUpdate() != 1) {
                    return false;
                }
            }
            audit(conn, workId, null, STATE_READY, worker,
                clip(reason, MAX_TEXT_LENGTH), now);
            return true;
        });
    }

    /**
     * Parks a row in {@code REVIEW} because the outcome of the side effect is unknown — a crash
     * mid-reward, a command that returned false, a provider that answered something unverifiable.
     * Nothing retries it automatically: paying a player twice is worse than asking an administrator.
     *
     * @return true when this call performed the transition
     */
    public boolean uncertain(String id, String token, String reason) throws SQLException {
        String workId = validateId(id);
        String claimToken = validateToken(token);
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            String worker = workerOf(conn, workId);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                    + " SET state = ?, claim_token = NULL, lease_until_ms = 0,"
                    + " next_attempt_at_ms = 0, last_error = ?, updated_at_ms = ?"
                    + " WHERE id = ? AND state IN (?, ?) AND claim_token = ?")) {
                stmt.setString(1, STATE_REVIEW);
                stmt.setString(2, clip(reason, MAX_TEXT_LENGTH));
                stmt.setLong(3, now);
                stmt.setString(4, workId);
                stmt.setString(5, STATE_CLAIMED);
                stmt.setString(6, STATE_RUNNING);
                stmt.setString(7, claimToken);
                if (stmt.executeUpdate() != 1) {
                    return false;
                }
            }
            audit(conn, workId, null, STATE_REVIEW, worker, clip(reason, MAX_TEXT_LENGTH), now);
            return true;
        });
    }

    /**
     * Releases the leases of workers that died, without ever guessing about an effect.
     *
     * <p>An expired {@code CLAIMED} row never started, so it goes back to {@code READY}. An expired
     * {@code RUNNING} row is retried only when the operation declared itself idempotent; otherwise
     * it goes to {@code REVIEW}. Each transition re-checks the lease it read, so a worker that
     * renewed in the meantime is left alone.
     *
     * @return how many rows were recovered
     */
    public int recoverExpired(int limit) throws SQLException {
        int bounded = clampLimit(limit);
        if (bounded <= 0) {
            return 0;
        }
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            List<Object[]> expired = new ArrayList<>();
            String sql = "SELECT id, state, retry_safe, claim_token FROM " + t(OUTBOX)
                + " WHERE state IN (?, ?) AND lease_until_ms <= ?"
                + " ORDER BY lease_until_ms, id LIMIT " + bounded + forUpdate();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, STATE_CLAIMED);
                stmt.setString(2, STATE_RUNNING);
                stmt.setLong(3, now);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        expired.add(new Object[]{rs.getString(1), rs.getString(2),
                            rs.getInt(3) != 0, rs.getString(4)});
                    }
                }
            }
            int recovered = 0;
            for (Object[] row : expired) {
                String workId = (String) row[0];
                String from = (String) row[1];
                boolean retrySafe = (Boolean) row[2];
                // A claimed row never began its effect; a running one may have.
                String target = STATE_CLAIMED.equals(from) || retrySafe ? STATE_READY : STATE_REVIEW;
                String reason = STATE_CLAIMED.equals(from)
                    ? "Lease expired before the effect started"
                    : retrySafe ? "Lease expired; operation declared idempotent"
                        : "Lease expired during a non-idempotent effect; outcome unknown";
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                        + " SET state = ?, claim_token = NULL, lease_until_ms = 0,"
                        + " next_attempt_at_ms = ?, last_error = ?, updated_at_ms = ?"
                        + " WHERE id = ? AND state = ? AND lease_until_ms <= ?")) {
                    stmt.setString(1, target);
                    stmt.setLong(2, STATE_READY.equals(target) ? now : 0L);
                    stmt.setString(3, reason);
                    stmt.setLong(4, now);
                    stmt.setString(5, workId);
                    stmt.setString(6, from);
                    stmt.setLong(7, now);
                    if (stmt.executeUpdate() != 1) {
                        continue;
                    }
                }
                audit(conn, workId, from, target, "recovery", reason, now);
                recovered++;
            }
            return recovered;
        });
    }

    /**
     * Administrator decision for a row parked in {@code REVIEW}.
     *
     * <p>Only {@code REVIEW} is eligible: {@code DONE} is never reopened and an active
     * {@code CLAIMED}/{@code RUNNING} row belongs to a live worker. The decision and its author are
     * written to the audit trail in the same transaction.
     *
     * @param retry true to make the row deliverable again, false to acknowledge it as delivered
     * @return true when this call performed the transition
     */
    public boolean resolve(String id, boolean retry, String actor, String reason) throws SQLException {
        String workId = validateId(id);
        String who = actor == null || actor.isBlank()
            ? "administrator" : clip(actor.trim(), MAX_ACTOR_LENGTH);
        String note = clip(reason, MAX_TEXT_LENGTH);
        return inTransaction(conn -> {
            long now = databaseNow(conn);
            String target = retry ? STATE_READY : STATE_DONE;
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + t(OUTBOX)
                    + " SET state = ?, claim_token = NULL, lease_until_ms = 0,"
                    + " next_attempt_at_ms = ?, last_error = ?, updated_at_ms = ?"
                    + " WHERE id = ? AND state = ?")) {
                stmt.setString(1, target);
                stmt.setLong(2, retry ? now : 0L);
                stmt.setString(3, note == null || note.isEmpty() ? who + " resolved" : who + ": " + note);
                stmt.setLong(4, now);
                stmt.setString(5, workId);
                stmt.setString(6, STATE_REVIEW);
                if (stmt.executeUpdate() != 1) {
                    return false;
                }
            }
            audit(conn, workId, STATE_REVIEW, target, who, note, now);
            return true;
        });
    }

    // ───────────────────────── internals ─────────────────────────

    @FunctionalInterface
    private interface Transaction<T> {
        T run(Connection conn) throws SQLException;
    }

    /**
     * Runs one write transaction, retrying only contention errors (SQLite busy, MySQL deadlock or
     * lock wait) a bounded number of times. A failed attempt is rolled back before the retry, so a
     * retry never sees a half-applied change.
     */
    private <T> T inTransaction(Transaction<T> work) throws SQLException {
        int attempts = mysql ? MAX_MYSQL_ATTEMPTS : MAX_SQLITE_ATTEMPTS;
        SQLException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return runOnce(work);
            } catch (SQLException e) {
                last = e;
                if (attempt >= attempts || !isContention(e)) {
                    throw e;
                }
                sleepQuietly(RETRY_BACKOFF_MILLIS * attempt);
            }
        }
        throw last == null ? new SQLException("投递事务失败") : last;
    }

    private <T> T runOnce(Transaction<T> work) throws SQLException {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                prepare(conn);
                acquireWriteLock(conn);
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    /**
     * SQLite takes its write lock <em>before</em> the first read: a deferred transaction that reads
     * first and writes later can fail its lock upgrade while another writer holds the lock. Every
     * write path here therefore starts with a no-op write, which is what actually claims the
     * RESERVED lock; MySQL locks rows individually instead and needs nothing.
     */
    private void acquireWriteLock(Connection conn) throws SQLException {
        if (mysql) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE " + t(OUTBOX) + " SET updated_at_ms = updated_at_ms WHERE 1 = 0");
        }
    }

    private Work readById(Connection conn, String workId) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + t(OUTBOX) + " WHERE id = ?" + forUpdate();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, workId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private List<Work> readAll(PreparedStatement stmt) throws SQLException {
        List<Work> result = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        }
        return result;
    }

    private static Work map(ResultSet rs) throws SQLException {
        String recipient = rs.getString(2);
        UUID recipientUuid;
        try {
            recipientUuid = UUID.fromString(recipient);
        } catch (IllegalArgumentException | NullPointerException corrupted) {
            // A corrupted recipient must not hide the row from an administrator.
            recipientUuid = new UUID(0L, 0L);
        }
        return new Work(rs.getString(1), recipientUuid, rs.getString(3), rs.getString(4),
            rs.getInt(5) != 0, rs.getString(6), rs.getInt(7), rs.getString(8), rs.getLong(9),
            rs.getLong(10), rs.getString(11), rs.getLong(12), rs.getLong(13));
    }

    /** The worker that currently owns the row, for the audit trail; null when nobody does. */
    private String workerOf(Connection conn, String workId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT worker_id FROM " + t(OUTBOX) + " WHERE id = ?")) {
            stmt.setString(1, workId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void audit(Connection conn, String workId, String fromState, String toState,
                       String actor, String reason, long now) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO " + t(AUDIT)
                + " (work_id, from_state, to_state, actor, reason, at_ms) VALUES (?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, workId);
            stmt.setString(2, fromState);
            stmt.setString(3, toState);
            stmt.setString(4, clip(actor, MAX_ACTOR_LENGTH));
            stmt.setString(5, clip(reason, MAX_TEXT_LENGTH));
            stmt.setLong(6, now);
            stmt.executeUpdate();
        }
    }

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
     * neither reads the JVM clock, so a skewed backend cannot stretch or expire a lease early.
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

    private static String validatePrefix(String prefix) {
        String candidate = prefix == null ? "" : prefix;
        if (!SAFE_PREFIX.matcher(candidate).matches()) {
            throw new IllegalArgumentException("非法的数据库表前缀: " + candidate);
        }
        return candidate;
    }

    private static String validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("投递任务 id 不能为空");
        }
        String trimmed = id.trim();
        if (trimmed.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("投递任务 id 过长: " + trimmed.length());
        }
        return trimmed;
    }

    private static String validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("投递任务令牌不能为空");
        }
        return token;
    }

    private static long clampLease(long leaseMillis) {
        if (leaseMillis <= 0L) {
            return 1L;
        }
        return Math.min(leaseMillis, MAX_LEASE_MILLIS);
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return 0;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        TOKENS.nextBytes(bytes);
        StringBuilder token = new StringBuilder(32);
        for (byte b : bytes) {
            token.append(Character.forDigit((b >> 4) & 0xF, 16));
            token.append(Character.forDigit(b & 0xF, 16));
        }
        return token.toString();
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0L) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    private static boolean isContention(SQLException e) {
        int code = e.getErrorCode();
        if (code == 5 || code == 6) {
            return true;
        }
        if (code == 1213 || code == 1205) {
            return true;
        }
        String state = e.getSQLState();
        if ("40001".equals(state)) {
            return true;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("database is locked") || message.contains("database table is locked")
            || message.contains("sqlite_busy") || message.contains("deadlock found")
            || message.contains("lock wait timeout");
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
}
