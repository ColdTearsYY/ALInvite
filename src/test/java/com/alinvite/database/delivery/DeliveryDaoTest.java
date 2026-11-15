package com.alinvite.database.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real production outbox SQL ({@link DeliveryDao}) against real SQLite files.
 *
 * <p>The database clock is injected, so a lease expires exactly when a test says it does: every
 * deadline in this class is {@code now + lease}, and advancing the clock is the only way time
 * moves. That is what lets these tests assert exact states instead of sleeping.
 */
class DeliveryDaoTest {

    private static final String PREFIX = "alinvite_";
    private static final long SECOND = 1000L;

    @TempDir
    Path tempDir;

    private Path dbFile;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private DeliveryDao dao;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tempDir.resolve("delivery-" + UUID.randomUUID() + ".db");
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }
        dao = newDao();
        dao.ensureSchema();
    }

    // ───────────────────────── enqueue ─────────────────────────

    @Test
    void enqueueInsideCallerTransactionRollsBackAtomically() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            assertEquals(2, dao.enqueue(conn, List.of(
                new DeliveryDao.WorkSpec("bind:" + player + ":whitelist", player, "whitelist", "{}", true),
                new DeliveryDao.WorkSpec("bind:" + player + ":gift:0", player, "reward", "{\"v\":1}", false))));
            // The caller decided the business change was not valid after all.
            conn.rollback();
        }
        assertNull(dao.find("bind:" + player + ":whitelist"));
        assertEquals(0, dao.list(null, 10).size());
    }

    @Test
    void enqueueInsideCallerTransactionIsInvisibleUntilCommit() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            dao.enqueue(conn, List.of(spec("bind:commit", player)));
            // Not committed yet: another connection must not see it.
            assertNull(dao.find("bind:commit"));
            conn.commit();
        }
        assertNotNull(dao.find("bind:commit"));
    }

    @Test
    void duplicateIdKeepsTheOriginalSnapshot() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals(1, dao.enqueue(List.of(new DeliveryDao.WorkSpec(
            "bind:dup", player, "whitelist", "{\"v\":1}", true))));
        DeliveryDao.Work first = dao.find("bind:dup");

        // The bind is replayed with a different kind, payload and retry flag: nothing may change.
        assertEquals(0, dao.enqueue(List.of(new DeliveryDao.WorkSpec(
            "bind:dup", player, "reward", "{\"v\":2}", false))));
        DeliveryDao.Work after = dao.find("bind:dup");

        assertEquals(first.payload(), after.payload());
        assertEquals("whitelist", after.kind());
        assertTrue(after.retrySafe());
        assertEquals(first.createdAt(), after.createdAt());
        assertEquals(first.state(), after.state());
        assertEquals(1, dao.list(null, 10).size());
    }

    @Test
    void enqueueToleratesEmptyAndNullLists() throws Exception {
        assertEquals(0, dao.enqueue((List<DeliveryDao.WorkSpec>) null));
        assertEquals(0, dao.enqueue(List.of()));
        assertEquals(0, dao.list(null, 10).size());
    }

    // ───────────────────────── claim ─────────────────────────

    @Test
    void claimMovesReadyToClaimedAndOnlyOnce() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:lease", player)));

        assertNull(dao.claim("bind:missing", "w1", 60 * SECOND), "unknown id must not be claimable");

        DeliveryDao.Work claimed = dao.claim("bind:lease", "w1", 60 * SECOND);
        assertNotNull(claimed);
        assertEquals(DeliveryDao.STATE_CLAIMED, claimed.state());
        assertNotNull(claimed.claimToken());
        assertEquals(1, claimed.attempts());

        // A second worker finds no READY row, so only one worker is ever inside the row.
        assertNull(dao.claim("bind:lease", "w2", 60 * SECOND));
        assertEquals(1, dao.find("bind:lease").attempts());
        assertEquals(0, dao.ready(null, 10).size());
    }

    @Test
    void simultaneousClaimsHaveExactlyOneWinner() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:race", player)));

        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<DeliveryDao.Work> claimed = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        try {
            for (int i = 0; i < workers; i++) {
                final String worker = "w" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        DeliveryDao.Work work = dao.claim("bind:race", worker, 60 * SECOND);
                        if (work != null) {
                            winners.incrementAndGet();
                            claimed.add(work);
                        }
                    } catch (Exception e) {
                        failed.set(true);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "claimers must finish");
        } finally {
            pool.shutdownNow();
        }

        assertFalse(failed.get(), "no claimer may fail");
        assertEquals(1, winners.get(), "exactly one worker may own the row");
        assertEquals(1, claimed.size());
        assertEquals(DeliveryDao.STATE_CLAIMED, dao.find("bind:race").state());
        assertEquals(1, dao.find("bind:race").attempts(), "losing claims must not count as attempts");
    }

    @Test
    void beginRequiresTheCurrentTokenAndALiveLease() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:begin", player)));
        DeliveryDao.Work claimed = dao.claim("bind:begin", "w1", 5 * SECOND);
        assertNotNull(claimed);

        assertFalse(dao.begin("bind:begin", "not-the-token", 5 * SECOND));
        assertTrue(dao.begin("bind:begin", claimed.claimToken(), 5 * SECOND));
        assertEquals(DeliveryDao.STATE_RUNNING, dao.find("bind:begin").state());

        // Already RUNNING: begin is not a re-entry point.
        assertFalse(dao.begin("bind:begin", claimed.claimToken(), 5 * SECOND));
    }

    @Test
    void beginRefusesALeaseThatAlreadyExpired() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:late", player)));
        DeliveryDao.Work claimed = dao.claim("bind:late", "w1", 1 * SECOND);
        assertNotNull(claimed);

        advance(2 * SECOND);
        // The worker was paused across its deadline: it must not start the side effect.
        assertFalse(dao.begin("bind:late", claimed.claimToken(), 1 * SECOND));
        assertEquals(DeliveryDao.STATE_CLAIMED, dao.find("bind:late").state());
    }

    // ───────────────────────── recovery and fencing ─────────────────────────

    @Test
    void expiredClaimReturnsToReadyEvenForUnsafeWork() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(new DeliveryDao.WorkSpec("bind:never-started", player, "reward", "{}", false)));
        DeliveryDao.Work claimed = dao.claim("bind:never-started", "w1", 1 * SECOND);
        assertNotNull(claimed);

        advance(2 * SECOND);
        assertEquals(1, dao.recoverExpired(10));

        DeliveryDao.Work recovered = dao.find("bind:never-started");
        // The effect provably never began, so retrying is safe even for a non-idempotent reward.
        assertEquals(DeliveryDao.STATE_READY, recovered.state());
        assertNull(recovered.claimToken(), "the old owner must lose its token");
        assertEquals(0, recovered.leaseUntil());
        assertEquals(1, dao.ready(null, 10).size());
    }

    @Test
    void expiredRunningWorkRetriesOnlyWhenDeclaredIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(
            new DeliveryDao.WorkSpec("bind:safe", player, "whitelist", "{}", true),
            new DeliveryDao.WorkSpec("bind:unsafe", player, "reward", "{}", false)));

        DeliveryDao.Work safe = dao.claim("bind:safe", "w1", 1 * SECOND);
        DeliveryDao.Work unsafe = dao.claim("bind:unsafe", "w1", 1 * SECOND);
        assertNotNull(safe);
        assertNotNull(unsafe);
        assertTrue(dao.begin("bind:safe", safe.claimToken(), 1 * SECOND));
        assertTrue(dao.begin("bind:unsafe", unsafe.claimToken(), 1 * SECOND));

        advance(2 * SECOND);
        assertEquals(2, dao.recoverExpired(10));

        assertEquals(DeliveryDao.STATE_READY, dao.find("bind:safe").state());
        // A reward that may already have been paid is never retried automatically.
        assertEquals(DeliveryDao.STATE_REVIEW, dao.find("bind:unsafe").state());
        assertTrue(dao.find("bind:unsafe").lastError().toLowerCase().contains("unknown"));

        // The stale workers cannot report anything any more.
        assertFalse(dao.finish("bind:safe", safe.claimToken()));
        assertFalse(dao.finish("bind:unsafe", unsafe.claimToken()));
        assertFalse(dao.uncertain("bind:unsafe", unsafe.claimToken(), "late"));
        assertEquals(DeliveryDao.STATE_REVIEW, dao.find("bind:unsafe").state());
    }

    @Test
    void staleWorkerCannotFinishAfterSomeoneElseReclaimed() throws Exception {
        UUID player = UUID.randomUUID();
        // Whitelist registration is idempotent, so an expired RUNNING row is retried rather than
        // parked in REVIEW; a non-idempotent reward would (correctly) never be reclaimed here.
        dao.enqueue(List.of(new DeliveryDao.WorkSpec("bind:stale", player, "whitelist", "{}", true)));

        DeliveryDao.Work first = dao.claim("bind:stale", "w1", 1 * SECOND);
        assertNotNull(first);
        assertTrue(dao.begin("bind:stale", first.claimToken(), 1 * SECOND));

        advance(2 * SECOND);
        assertEquals(1, dao.recoverExpired(10));

        DeliveryDao.Work second = dao.claim("bind:stale", "w2", 60 * SECOND);
        assertNotNull(second, "recovered work must be claimable again");
        assertFalse(first.claimToken().equals(second.claimToken()), "every retry gets a fresh token");
        assertTrue(dao.begin("bind:stale", second.claimToken(), 60 * SECOND));

        // The first worker wakes up and tries to report: its token is dead.
        assertFalse(dao.finish("bind:stale", first.claimToken()));
        assertFalse(dao.defer("bind:stale", first.claimToken(), "stale", 0L));
        assertFalse(dao.uncertain("bind:stale", first.claimToken(), "stale"));
        assertEquals(DeliveryDao.STATE_RUNNING, dao.find("bind:stale").state());

        assertTrue(dao.finish("bind:stale", second.claimToken()));
        assertEquals(DeliveryDao.STATE_DONE, dao.find("bind:stale").state());
        assertEquals(2, dao.find("bind:stale").attempts());
    }

    @Test
    void recoveryLeavesLiveLeasesAlone() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:live", player)));
        DeliveryDao.Work claimed = dao.claim("bind:live", "w1", 60 * SECOND);
        assertNotNull(claimed);

        assertEquals(0, dao.recoverExpired(10));
        assertEquals(DeliveryDao.STATE_CLAIMED, dao.find("bind:live").state());
        assertEquals(claimed.claimToken(), dao.find("bind:live").claimToken());
    }

    // ───────────────────────── retry / review ─────────────────────────

    @Test
    void deferDelaysEligibilityAndInvalidatesTheToken() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:defer", player)));
        DeliveryDao.Work claimed = dao.claim("bind:defer", "w1", 60 * SECOND);
        assertNotNull(claimed);
        assertTrue(dao.begin("bind:defer", claimed.claimToken(), 60 * SECOND));

        assertTrue(dao.defer("bind:defer", claimed.claimToken(), "Recipient offline", 15 * SECOND));
        DeliveryDao.Work deferred = dao.find("bind:defer");
        assertEquals(DeliveryDao.STATE_READY, deferred.state());
        assertNull(deferred.claimToken());
        assertEquals("Recipient offline", deferred.lastError());
        assertEquals(0, dao.ready(null, 10).size(), "a deferred row is not eligible yet");
        assertNull(dao.claim("bind:defer", "w2", 60 * SECOND), "claim must respect the delay");

        advance(15 * SECOND);
        assertEquals(1, dao.ready(null, 10).size());
        assertNotNull(dao.claim("bind:defer", "w2", 60 * SECOND));
        // The previous owner cannot act on the row any more.
        assertFalse(dao.finish("bind:defer", claimed.claimToken()));
    }

    @Test
    void uncertainParksTheRowAndNothingRetriesIt() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(new DeliveryDao.WorkSpec("bind:review", player, "command", "{}", true)));
        DeliveryDao.Work claimed = dao.claim("bind:review", "w1", 60 * SECOND);
        assertNotNull(claimed);
        assertTrue(dao.begin("bind:review", claimed.claimToken(), 60 * SECOND));

        assertTrue(dao.uncertain("bind:review", claimed.claimToken(), "Command returned false"));
        assertEquals(DeliveryDao.STATE_REVIEW, dao.find("bind:review").state());
        assertEquals(0, dao.ready(null, 10).size());
        assertEquals(0, dao.recoverExpired(10), "review is not a lease to recover");
        // Even an idempotent row in REVIEW waits for a human.
        advance(60 * SECOND);
        assertEquals(0, dao.ready(null, 10).size());
    }

    @Test
    void partialSuccessKeepsEveryRowIndependent() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(
            new DeliveryDao.WorkSpec("bind:multi:0", player, "reward", "{\"i\":0}", false),
            new DeliveryDao.WorkSpec("bind:multi:1", player, "reward", "{\"i\":1}", false),
            new DeliveryDao.WorkSpec("bind:multi:2", player, "reward", "{\"i\":2}", false)));

        DeliveryDao.Work zero = dao.claim("bind:multi:0", "w1", 60 * SECOND);
        DeliveryDao.Work one = dao.claim("bind:multi:1", "w1", 60 * SECOND);
        DeliveryDao.Work two = dao.claim("bind:multi:2", "w1", 60 * SECOND);
        assertNotNull(zero);
        assertNotNull(one);
        assertNotNull(two);
        assertTrue(dao.begin("bind:multi:0", zero.claimToken(), 60 * SECOND));
        assertTrue(dao.begin("bind:multi:1", one.claimToken(), 60 * SECOND));
        assertTrue(dao.begin("bind:multi:2", two.claimToken(), 60 * SECOND));

        assertTrue(dao.finish("bind:multi:0", zero.claimToken()));
        assertTrue(dao.defer("bind:multi:1", one.claimToken(), "Inventory full", 5 * SECOND));
        assertTrue(dao.uncertain("bind:multi:2", two.claimToken(), "Partial inventory acceptance"));

        assertEquals(DeliveryDao.STATE_DONE, dao.find("bind:multi:0").state());
        assertEquals(DeliveryDao.STATE_READY, dao.find("bind:multi:1").state());
        assertEquals(DeliveryDao.STATE_REVIEW, dao.find("bind:multi:2").state());
        // Each row kept its own payload: one reward failing never rewrote its siblings.
        assertEquals("{\"i\":0}", dao.find("bind:multi:0").payload());
        assertEquals("{\"i\":1}", dao.find("bind:multi:1").payload());
        assertEquals("{\"i\":2}", dao.find("bind:multi:2").payload());
    }

    @Test
    void doneIsNeverReplayed() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:done", player)));
        DeliveryDao.Work claimed = dao.claim("bind:done", "w1", 60 * SECOND);
        assertNotNull(claimed);
        assertTrue(dao.begin("bind:done", claimed.claimToken(), 60 * SECOND));
        assertTrue(dao.finish("bind:done", claimed.claimToken()));

        assertEquals(DeliveryDao.STATE_DONE, dao.find("bind:done").state());
        assertNull(dao.claim("bind:done", "w2", 60 * SECOND), "DONE is never claimable again");
        assertFalse(dao.finish("bind:done", claimed.claimToken()), "a second finish is a no-op");
        assertFalse(dao.defer("bind:done", claimed.claimToken(), "retry", 0L));
        assertFalse(dao.uncertain("bind:done", claimed.claimToken(), "retry"));
        assertFalse(dao.resolve("bind:done", true, "admin", "please retry"));
        // Re-enqueuing the same deterministic id must not resurrect it either.
        assertEquals(0, dao.enqueue(List.of(spec("bind:done", player))));
        assertEquals(DeliveryDao.STATE_DONE, dao.find("bind:done").state());
        assertEquals(0, dao.recoverExpired(10));
        assertEquals(0, dao.ready(null, 10).size());
    }

    // ───────────────────────── administration ─────────────────────────

    @Test
    void resolveOnlyTouchesReviewRowsAndIsAudited() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(
            new DeliveryDao.WorkSpec("bind:admin:ready", player, "reward", "{}", false),
            new DeliveryDao.WorkSpec("bind:admin:running", player, "reward", "{}", false),
            new DeliveryDao.WorkSpec("bind:admin:review", player, "reward", "{}", false)));

        DeliveryDao.Work running = dao.claim("bind:admin:running", "w1", 60 * SECOND);
        assertNotNull(running);
        assertTrue(dao.begin("bind:admin:running", running.claimToken(), 60 * SECOND));

        DeliveryDao.Work review = dao.claim("bind:admin:review", "w1", 60 * SECOND);
        assertNotNull(review);
        assertTrue(dao.begin("bind:admin:review", review.claimToken(), 60 * SECOND));
        assertTrue(dao.uncertain("bind:admin:review", review.claimToken(), "Provider timeout"));

        // Not eligible: still READY, and owned by a live worker.
        assertFalse(dao.resolve("bind:admin:ready", true, "admin", "nope"));
        assertFalse(dao.resolve("bind:admin:running", true, "admin", "nope"));
        assertFalse(dao.resolve("bind:missing", true, "admin", "nope"));

        assertTrue(dao.resolve("bind:admin:review", true, "admin", "Retry after manual check"));
        DeliveryDao.Work retried = dao.find("bind:admin:review");
        assertEquals(DeliveryDao.STATE_READY, retried.state());
        assertNull(retried.claimToken());
        // bind:admin:ready was never touched and is still READY, so the retried row makes two.
        List<DeliveryDao.Work> deliverable = dao.ready(null, 10);
        assertEquals(2, deliverable.size(), "the retried row is deliverable again");
        assertTrue(deliverable.stream().anyMatch(work -> "bind:admin:review".equals(work.id())),
            "the resolved row itself is eligible");

        // A second review, this time acknowledged as delivered.
        DeliveryDao.Work again = dao.claim("bind:admin:review", "w2", 60 * SECOND);
        assertNotNull(again);
        assertTrue(dao.begin("bind:admin:review", again.claimToken(), 60 * SECOND));
        assertTrue(dao.uncertain("bind:admin:review", again.claimToken(), "Still unknown"));
        assertTrue(dao.resolve("bind:admin:review", false, "admin", "Confirmed delivered"));
        assertEquals(DeliveryDao.STATE_DONE, dao.find("bind:admin:review").state());
        assertFalse(dao.resolve("bind:admin:review", true, "admin", "too late"));

        List<DeliveryDao.AuditEntry> history = dao.history("bind:admin:review", 50);
        assertFalse(history.isEmpty());
        assertTrue(history.stream().anyMatch(entry -> "admin".equals(entry.actor())
            && DeliveryDao.STATE_DONE.equals(entry.toState())
            && "Confirmed delivered".equals(entry.reason())));
        // Newest first.
        assertEquals(DeliveryDao.STATE_DONE, history.get(0).toState());
    }

    // ───────────────────────── persistence and queries ─────────────────────────

    @Test
    void stateAndAuditSurviveAReopen() throws Exception {
        UUID player = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:persist", player)));
        DeliveryDao.Work claimed = dao.claim("bind:persist", "w1", 60 * SECOND);
        assertNotNull(claimed);
        assertTrue(dao.begin("bind:persist", claimed.claimToken(), 60 * SECOND));
        assertTrue(dao.finish("bind:persist", claimed.claimToken()));

        DeliveryDao restarted = newDao();
        restarted.ensureSchema();
        DeliveryDao.Work reopened = restarted.find("bind:persist");
        assertEquals(DeliveryDao.STATE_DONE, reopened.state());
        assertEquals(1, reopened.attempts());
        assertFalse(restarted.history("bind:persist", 10).isEmpty());
        assertEquals(0, restarted.ready(null, 10).size());

        // A restart may not re-deliver anything that was already finished.
        assertNull(restarted.claim("bind:persist", "w2", 60 * SECOND));
    }

    @Test
    void listIsNewestFirstAndFiltersByRecipient() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:alice:1", alice)));
        advance(SECOND);
        dao.enqueue(List.of(spec("bind:alice:2", alice)));
        advance(SECOND);
        dao.enqueue(List.of(spec("bind:bob:1", bob)));

        List<DeliveryDao.Work> everything = dao.list(null, 10);
        assertEquals(3, everything.size());
        assertEquals("bind:bob:1", everything.get(0).id());

        List<DeliveryDao.Work> onlyAlice = dao.list(alice, 10);
        assertEquals(2, onlyAlice.size());
        assertEquals("bind:alice:2", onlyAlice.get(0).id());
        assertEquals(alice, onlyAlice.get(0).recipientUuid());

        assertEquals(1, dao.list(null, 1).size(), "limit is honoured");
        assertEquals(0, dao.list(null, 0).size());
    }

    @Test
    void readyFiltersByRecipientAndRespectsTheLimit() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        dao.enqueue(List.of(spec("bind:r:alice", alice), spec("bind:r:bob", bob)));

        assertEquals(2, dao.ready(null, 10).size());
        assertEquals(1, dao.ready(alice, 10).size());
        assertEquals("bind:r:alice", dao.ready(alice, 10).get(0).id());
        assertEquals(1, dao.ready(null, 1).size());
        assertEquals(0, dao.ready(UUID.randomUUID(), 10).size());
    }

    @Test
    void milestoneExpansionCommitsOnceAndPreservesChildren() throws Exception {
        UUID recipient = UUID.randomUUID();
        dao.enqueue(List.of(new DeliveryDao.WorkSpec("parent", recipient, "milestone", "{}", true)));
        var work = dao.claim("parent", "worker", 60 * SECOND);
        assertTrue(dao.begin("parent", work.claimToken(), 60 * SECOND));
        assertTrue(dao.finishAndEnqueue("parent", work.claimToken(), List.of(spec("child", recipient))));
        assertEquals(DeliveryDao.STATE_DONE, dao.find("parent").state());
        assertEquals(DeliveryDao.STATE_READY, newDao().find("child").state());
        assertFalse(dao.finishAndEnqueue("parent", work.claimToken(), List.of(spec("extra", recipient))));
        assertNull(dao.find("extra"));
        assertEquals("reward items queued", dao.history("parent", 1).get(0).reason());
    }

    @Test
    void milestoneExpansionFailureRollsBackChildrenAndParent() throws Exception {
        UUID recipient = UUID.randomUUID();
        dao.enqueue(List.of(new DeliveryDao.WorkSpec("parent", recipient, "milestone", "{}", true)));
        var work = dao.claim("parent", "worker", 60 * SECOND);
        assertTrue(dao.begin("parent", work.claimToken(), 60 * SECOND));
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_child BEFORE INSERT ON alinvite_delivery_outbox "
                + "WHEN NEW.id='bad-child' BEGIN SELECT RAISE(ABORT, 'injected child failure'); END");
        }
        assertThrows(SQLException.class, () -> dao.finishAndEnqueue("parent", work.claimToken(),
            List.of(spec("good-child", recipient), spec("bad-child", recipient))));
        assertNull(dao.find("good-child"));
        assertNull(dao.find("bad-child"));
        assertEquals(DeliveryDao.STATE_RUNNING, dao.find("parent").state());
    }

    @Test
    void expiredParentCannotExpandAfterRecoveryOrNewClaim() throws Exception {
        UUID recipient = UUID.randomUUID();
        dao.enqueue(List.of(new DeliveryDao.WorkSpec("parent", recipient, "milestone", "{}", true)));
        var old = dao.claim("parent", "old", 60 * SECOND);
        assertTrue(dao.begin("parent", old.claimToken(), 60 * SECOND));
        advance(60 * SECOND);
        assertFalse(dao.finishAndEnqueue("parent", old.claimToken(), List.of(spec("stale", recipient))));
        dao.recoverExpired(10);
        var current = dao.claim("parent", "current", 60 * SECOND);
        assertNotNull(current);
        assertTrue(dao.begin("parent", current.claimToken(), 60 * SECOND));
        assertFalse(dao.finishAndEnqueue("parent", old.claimToken(), List.of(spec("stale", recipient))));
        assertNull(dao.find("stale"));
        assertTrue(dao.finishAndEnqueue("parent", current.claimToken(), List.of(spec("valid", recipient))));
    }

    @Test
    void whitelistReadyFilterExcludesOtherKindsActiveClaimsAndDelayedWork() throws Exception {
        UUID recipient = UUID.randomUUID();
        dao.enqueue(List.of(new DeliveryDao.WorkSpec("white", recipient, "whitelist", "{}", true), spec("gift", recipient)));
        assertEquals(List.of("white"), dao.readyKind("whitelist", 10).stream().map(DeliveryDao.Work::id).toList());
        var work = dao.claim("white", "worker", 60 * SECOND);
        assertTrue(dao.readyKind("whitelist", 10).isEmpty());
        dao.defer("white", work.claimToken(), "offline test", SECOND);
        assertTrue(dao.readyKind("whitelist", 10).isEmpty());
        advance(SECOND);
        assertEquals(1, dao.readyKind("whitelist", 1).size());
        assertTrue(dao.readyKind("whitelist", 0).isEmpty());
    }

    // ───────────────────────── schema and guards ─────────────────────────

    @Test
    void ensureSchemaIsIdempotent() throws Exception {
        dao.ensureSchema();
        dao.ensureSchema();
        assertEquals(0, dao.list(null, 10).size());
        assertEquals(0, dao.history("bind:none", 10).size());
    }

    @Test
    void unsafePrefixAndBlankIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeliveryDao(this::open, "bad prefix;", false));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryDao(this::open, "x`y", false));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryDao(this::open, "a".repeat(33), false));
        assertThrows(IllegalArgumentException.class,
            () -> new DeliveryDao.WorkSpec("  ", UUID.randomUUID(), "reward", "{}", false));
        assertThrows(IllegalArgumentException.class,
            () -> new DeliveryDao.WorkSpec("a".repeat(161), UUID.randomUUID(), "reward", "{}", false));
    }

    @Test
    void mysqlSchemaToleratesOnlyTheDuplicateIndexError() throws Exception {
        AtomicInteger errorCode = new AtomicInteger(1061);
        DeliveryDao mysqlSchema = new DeliveryDao(() -> (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class[]{Connection.class}, (conn, method, args) -> {
                if (method.getName().equals("createStatement")) {
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                        new Class[]{Statement.class}, (statement, action, values) -> {
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
                                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                    new Class[]{ResultSet.class},
                                    (result, read, params) -> read.getName().equals("next") ? false : null);
                            }
                            return null;
                        });
                }
                return null;
            }), PREFIX, true);

        // 1061 means another backend created the index first: that one is expected and ignored.
        mysqlSchema.ensureSchema();
        // Any other SQL error is a real failure and must surface.
        errorCode.set(1064);
        assertEquals(1064, assertThrows(SQLException.class, mysqlSchema::ensureSchema).getErrorCode());
    }

    // ───────────────────────── helpers ─────────────────────────

    private DeliveryDao newDao() {
        return new DeliveryDao(this::open, PREFIX, false, clock::get);
    }

    private DeliveryDao.WorkSpec spec(String id, UUID recipient) {
        return new DeliveryDao.WorkSpec(id, recipient, "reward", "{}", false);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    private void advance(long millis) {
        clock.addAndGet(millis);
    }
}
