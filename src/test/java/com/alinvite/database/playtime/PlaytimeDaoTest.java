package com.alinvite.database.playtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real production online-time SQL ({@link PlaytimeDao}) against real SQLite files.
 *
 * <p>The database clock is injected so every scenario is deterministic: an interval is placed on the
 * shared timeline by {@code now - reportAge - elapsed}, so fixing {@code now} fixes the interval
 * exactly. That is what lets these tests assert exact milliseconds instead of ranges.
 */
class PlaytimeDaoTest {

    private static final String PREFIX = "alinvite_";

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60L * MINUTE;

    @TempDir
    Path tempDir;

    private Path dbFile;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private PlaytimeDao dao;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("playtime-" + UUID.randomUUID() + ".db");
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            // Production guarantee: the invite tables exist before ensureSchema.
            stmt.execute("CREATE TABLE alinvite_players ("
                + " uuid VARCHAR(36) PRIMARY KEY,"
                + " quota_high_water_minutes BIGINT NOT NULL DEFAULT 0)");
        }
        dao = newDao();
        dao.ensureSchema();
    }

    // ───────────────────────── accumulation ─────────────────────────

    @Test
    void sequentialSessionsAddUpAndOfflineGapIsNotCounted() throws Exception {
        UUID player = UUID.randomUUID();
        UUID first = UUID.randomUUID();

        assertEquals(30 * HOUR, dao.record(player, first, 30 * HOUR, 0L));

        // Two hours offline, then an eighteen hour stretch: the anchor of the second session sits
        // two hours after the first one ended, so the gap is simply not covered by any interval.
        advance(2 * HOUR + 18 * HOUR);
        assertEquals(48 * HOUR, dao.record(player, UUID.randomUUID(), 18 * HOUR, 0L));
        assertEquals(48 * HOUR, dao.getTotalMillis(player));
    }

    @Test
    void duplicateAndOutOfOrderReportsAreIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        assertEquals(10 * MINUTE, dao.record(player, session, 10 * MINUTE, 0L));

        // Retry of the very same report: the interval is already covered, nothing is added.
        assertEquals(10 * MINUTE, dao.record(player, session, 10 * MINUTE, 0L));
        assertEquals(10 * MINUTE, dao.record(player, session, 10 * MINUTE, 0L));

        // Out-of-order (smaller) report: the session already reported more, so it is a no-op.
        assertEquals(10 * MINUTE, dao.record(player, session, 5 * MINUTE, 0L));

        // A later final report of the same stretch extends the same interval.
        assertEquals(20 * MINUTE, dao.record(player, session, 20 * MINUTE, 0L));
        assertEquals(20 * MINUTE, dao.getTotalMillis(player));
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_playtime_intervals"));
    }

    @Test
    void overlappingBackendSessionsAreCountedOnce() throws Exception {
        UUID player = UUID.randomUUID();
        UUID onBackendA = UUID.randomUUID();
        UUID onBackendB = UUID.randomUUID();

        // Both stretches end at the same instant: B's hour sits inside A's hour.
        assertEquals(60 * MINUTE, dao.record(player, onBackendA, 60 * MINUTE, 0L));
        assertEquals(60 * MINUTE, dao.record(player, onBackendB, 40 * MINUTE, 0L));
        assertEquals(60 * MINUTE, dao.getTotalMillis(player));
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_playtime_intervals"));
    }

    @Test
    void lateFinalReportMergesWithNewerSessionWithoutDoubleCounting() throws Exception {
        UUID player = UUID.randomUUID();
        UUID firstStretch = UUID.randomUUID();
        UUID secondStretch = UUID.randomUUID();

        // A played 30 minutes and reported its heartbeat at quit time.
        assertEquals(30 * MINUTE, dao.record(player, firstStretch, 30 * MINUTE, 0L));

        // One hour later the player is online again on another backend for 20 minutes.
        advance(60 * MINUTE);
        assertEquals(50 * MINUTE, dao.record(player, secondStretch, 20 * MINUTE, 0L));

        // The final quit report of the first stretch arrives late and says it actually lasted
        // 90 minutes: it now spans the gap and touches the second stretch, which was already paid.
        assertEquals(90 * MINUTE, dao.record(player, firstStretch, 90 * MINUTE, 0L));
        assertEquals(90 * MINUTE, dao.getTotalMillis(player));
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_playtime_intervals"));
    }

    @Test
    void reportAgePlacesQueuedReportBackOnTheTimeline() throws Exception {
        UUID player = UUID.randomUUID();

        // A 30 minute stretch whose report waited 10 minutes in a shutdown queue: it happened
        // earlier on the timeline, not later.
        assertEquals(30 * MINUTE, dao.record(player, UUID.randomUUID(), 30 * MINUTE, 10 * MINUTE));

        // The next stretch starts exactly when the queued one ended and runs to "now".
        assertEquals(40 * MINUTE, dao.record(player, UUID.randomUUID(), 10 * MINUTE, 0L));
        assertEquals(40 * MINUTE, dao.getTotalMillis(player));
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_playtime_intervals"));
    }

    // ───────────────────────── persistence ─────────────────────────

    @Test
    void totalsSurviveNewDaoInstanceAndReconnect() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals(3 * HOUR, dao.record(player, UUID.randomUUID(), 3 * HOUR, 0L));

        // A restart builds a brand new DAO on the same file: the ledger, not memory, is the truth.
        PlaytimeDao restarted = newDao();
        restarted.ensureSchema();
        assertEquals(3 * HOUR, restarted.getTotalMillis(player));
        // The player came back two hours after that stretch ended: the next interval starts at its end.
        advance(2 * HOUR);
        assertEquals(3 * HOUR + 2 * HOUR, restarted.record(player, UUID.randomUUID(), 2 * HOUR, 0L));
        assertEquals(5 * HOUR, dao.getTotalMillis(player));
    }

    @Test
    void legacyQuotaFloorIsImportedExactlyOnce() throws Exception {
        UUID player = UUID.randomUUID();
        insertLegacyFloor(player, 30 * 60); // 1800 minutes of invite-era playtime

        // Reading is enough: an existing offline player gets the imported floor without any report.
        assertEquals(30 * HOUR, dao.getTotalMillis(player));
        assertEquals(31 * HOUR, dao.record(player, UUID.randomUUID(), 1 * HOUR, 0L));

        // The invite plugin keeps raising its own high-water mark; that must never be re-imported.
        insertLegacyFloor(player, 5000);
        assertEquals(31 * HOUR, dao.getTotalMillis(player));

        // A player the invite plugin has never seen starts at zero.
        assertEquals(0L, dao.getTotalMillis(UUID.randomUUID()));
    }

    @Test
    void differentPlayersAreIsolated() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertEquals(2 * HOUR, dao.record(alice, UUID.randomUUID(), 2 * HOUR, 0L));
        assertEquals(5 * HOUR, dao.record(bob, UUID.randomUUID(), 5 * HOUR, 0L));
        assertEquals(2 * HOUR, dao.getTotalMillis(alice));
        assertEquals(5 * HOUR, dao.getTotalMillis(bob));

        // Same wall-clock window for two different accounts is two accounts' time, not one.
        assertEquals(2, countRows("SELECT COUNT(*) FROM alinvite_playtime_totals"));
    }

    // ───────────────────────── concurrency ─────────────────────────

    @Test
    void concurrentReportsProduceTheUnionOnce() throws Exception {
        UUID player = UUID.randomUUID();
        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean(false);
        try {
            for (int i = 0; i < writers; i++) {
                final long elapsed = (i + 1) * 10 * MINUTE;
                pool.submit(() -> {
                    try {
                        start.await();
                        dao.record(player, UUID.randomUUID(), elapsed, 0L);
                    } catch (Exception e) {
                        failed.set(true);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "writers must finish");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(!failed.get(), "no writer may fail");
        // Every window ends at the same frozen instant, so the union is the longest one.
        assertEquals(40 * MINUTE, dao.getTotalMillis(player));
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_playtime_totals"));
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_playtime_intervals"));
    }

    // ───────────────────────── failure handling ─────────────────────────

    @Test
    void failedIntervalWriteRollsBackSessionAndTotals() throws Exception {
        UUID player = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        assertEquals(0L, dao.getTotalMillis(player));

        AtomicBoolean armed = new AtomicBoolean(true);
        PlaytimeDao failing = new PlaytimeDao(() -> wrap(open(), armed), PREFIX, false, clock::get);

        assertThrows(SQLException.class, () -> failing.record(player, session, 45 * MINUTE, 0L));

        // Nothing half-written survives: no session, no interval, no total.
        assertEquals(0L, dao.getTotalMillis(player));
        assertEquals(0, countRows("SELECT COUNT(*) FROM alinvite_playtime_sessions"));
        assertEquals(0, countRows("SELECT COUNT(*) FROM alinvite_playtime_intervals"));

        // The retry of the same stretch after the failure counts exactly once.
        armed.set(false);
        assertEquals(45 * MINUTE, failing.record(player, session, 45 * MINUTE, 0L));
        assertEquals(45 * MINUTE, dao.getTotalMillis(player));
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_playtime_intervals"));
    }

    // ───────────────────────── schema and clock ─────────────────────────

    @Test
    void ensureSchemaIsIdempotent() throws Exception {
        dao.ensureSchema();
        dao.ensureSchema();
        assertEquals(0L, dao.getTotalMillis(UUID.randomUUID()));
    }

    @Test
    void unsafePrefixIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlaytimeDao(this::open, "bad prefix;", false));
        assertThrows(IllegalArgumentException.class,
            () -> new PlaytimeDao(this::open, "x`y", false));
        assertThrows(IllegalArgumentException.class,
            () -> new PlaytimeDao(this::open, "a".repeat(33), false));
    }

    @Test
    void productionDatabaseClockMeasuresExactElapsed() throws Exception {
        // The three argument constructor is the production path: no injected clock, the database
        // itself answers. Anchor = dbNow - elapsed and end = anchor + elapsed = dbNow, so the
        // reported stretch is exactly as long as it says regardless of the JVM clock.
        PlaytimeDao production = new PlaytimeDao(this::open, PREFIX, false);
        UUID player = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        assertEquals(HOUR, production.record(player, session, HOUR, 0L));
        assertEquals(HOUR, production.getTotalMillis(player));

        // A replay through the same session id adds nothing even though the database clock moved.
        assertEquals(HOUR, production.record(player, session, HOUR, 0L));
        assertEquals(HOUR, production.getTotalMillis(player));
    }

    @Test
    void connectionWaitAndInternalRetryDoNotShiftSessionTimeline() throws Exception {
        long sampledNow = clock.get();
        AtomicLong monotonic = new AtomicLong();
        AtomicBoolean first = new AtomicBoolean(true);
        PlaytimeDao delayed = new PlaytimeDao(() -> {
            clock.addAndGet(3000L);
            monotonic.addAndGet(TimeUnit.MILLISECONDS.toNanos(3000L));
            if (first.getAndSet(false)) throw new SQLException("database is locked", "", 5);
            return open();
        }, PREFIX, false, clock::get, monotonic::get);
        UUID player = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        assertEquals(MINUTE, delayed.record(player, session, MINUTE, 7000L));
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
                "SELECT started_at_ms FROM alinvite_playtime_sessions WHERE player_uuid=? AND session_id=?")) {
            stmt.setString(1, player.toString());
            stmt.setString(2, session.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(sampledNow - 7000L - MINUTE, rs.getLong(1));
            }
        }
    }

    @Test
    void confirmedLegacyTimeIsNotSilentlyCappedAtTenYears() throws Exception {
        UUID player = UUID.randomUUID();
        long minutes = 20L * 365L * 24L * 60L;
        insertLegacyFloor(player, minutes);
        assertEquals(minutes * 60000L, dao.getTotalMillis(player));
    }

    @Test
    void mysqlConcurrentIndexCreationIgnoresOnlyDuplicateIndexError() throws Exception {
        var errorCode = new java.util.concurrent.atomic.AtomicInteger(1061);
        PlaytimeDao mysqlSchema = new PlaytimeDao(() -> (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class[]{Connection.class}, (conn, method, args) -> {
                if (method.getName().equals("createStatement")) {
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class[]{Statement.class},
                        (statement, action, values) -> {
                            if (action.getName().equals("executeUpdate")) {
                                if (((String) values[0]).startsWith("CREATE INDEX")) {
                                    throw new SQLException("simulated MySQL DDL race", "42000", errorCode.get());
                                }
                                return 0;
                            }
                            return null;
                        });
                }
                if (method.getName().equals("prepareStatement")) {
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                        new Class[]{PreparedStatement.class}, (statement, action, values) -> {
                            if (action.getName().equals("executeQuery")) {
                                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class},
                                    (result, read, params) -> read.getName().equals("next") ? false : null);
                            }
                            return null;
                        });
                }
                return null;
            }), PREFIX, true);
        mysqlSchema.ensureSchema();
        errorCode.set(1064);
        assertEquals(1064, assertThrows(SQLException.class, mysqlSchema::ensureSchema).getErrorCode());
    }

    // ───────────────────────── helpers ─────────────────────────

    private PlaytimeDao newDao() {
        return new PlaytimeDao(this::open, PREFIX, false, clock::get);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    private void advance(long millis) {
        clock.addAndGet(millis);
    }

    private void insertLegacyFloor(UUID player, long minutes) throws SQLException {
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO alinvite_players (uuid, quota_high_water_minutes) VALUES (?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET quota_high_water_minutes = excluded.quota_high_water_minutes")) {
            stmt.setString(1, player.toString());
            stmt.setLong(2, minutes);
            stmt.executeUpdate();
        }
    }

    private int countRows(String sql) throws SQLException {
        try (Connection conn = open(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** A connection whose interval write fails on demand, to prove the whole transaction rolls back. */
    private Connection wrap(Connection real, AtomicBoolean armed) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (armed.get() && "prepareStatement".equals(method.getName())
                    && args != null && args.length > 0 && args[0] instanceof String sql
                    && sql.contains("playtime_intervals") && sql.contains("INSERT")) {
                    throw new SQLException("simulated interval write failure");
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}
