package com.alinvite.manager;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OnlineTimeTrackerTest {
    private final AtomicLong nanos = new AtomicLong();
    private final ManualExecutor io = new ManualExecutor();
    private final FakeStore store = new FakeStore();
    private final OnlineTimeTracker tracker = new OnlineTimeTracker(store, io, nanos::get);
    private final UUID player = UUID.randomUUID();

    @Test
    void snapshotsNeverPerformStorageOnCallerAndQuitFreezesDuration() {
        var join = tracker.joined(player);
        assertFalse(join.isDone());
        assertTrue(store.reports.isEmpty());
        advance(1000); io.drain();
        assertEquals(1000L, store.reports.get(0).age);
        advance(9000);
        var quit = tracker.left(player);
        advance(60000); io.drain();
        assertEquals(10000L, quit.join());
        assertEquals(60000L, store.reports.get(1).age);
        tracker.flushAll(); io.drain();
        assertEquals(10000L, store.total(player));
    }

    @Test
    void reconnectAddsSessionsButNotOfflineGapAndDuplicateJoinDoesNotRestartClock() {
        tracker.joined(player); io.drain();
        advance(10000); tracker.joined(player); io.drain();
        advance(10000); tracker.left(player); io.drain();
        advance(100000);
        tracker.joined(player); io.drain();
        advance(30000); tracker.left(player); io.drain();
        assertEquals(50000L, store.total(player));
        assertEquals(2, store.elapsed.size());
    }

    @Test
    void failedQuitRemainsRetryableWithoutAccruingOfflineTime() {
        tracker.joined(player); io.drain();
        advance(30000); store.failNext = true;
        var quit = tracker.left(player); io.drain();
        assertThrows(CompletionException.class, quit::join);
        advance(300000);
        var retry = tracker.flushAll(); io.drain(); retry.join();
        assertEquals(30000L, store.total(player));
        assertEquals(300000L, store.reports.get(store.reports.size() - 1).age);
    }

    @Test
    void ambiguousCommitRetryUsesSameSessionAndCannotAddTimeTwice() {
        tracker.joined(player); io.drain();
        advance(10000); store.failAfterCommit = true;
        var first = tracker.refresh(player); io.drain();
        assertThrows(CompletionException.class, first::join);
        var retry = tracker.refresh(player); io.drain();
        assertEquals(10000L, retry.join());
        assertEquals(1, store.elapsed.size());
    }

    @Test
    void shutdownAppendsTailBehindPendingWriteAndRejectsNewSessions() {
        tracker.joined(player); io.drain();
        advance(10000); tracker.refresh(player);
        advance(2000); var shutdown = tracker.close();
        advance(100000); io.drain(); shutdown.join();
        assertEquals(12000L, store.total(player));
        assertThrows(CompletionException.class, () -> tracker.joined(player).join());
    }

    @Test
    void coldPlaceholderReadIsAsyncAndNeverPretendsUnknownTimeIsZero() {
        assertEquals(-1L, tracker.cachedMillis(player));
        assertEquals(-1L, tracker.cachedMillis(player));
        assertEquals(1, io.tasks.size());
        io.drain();
        assertEquals(0L, tracker.cachedMillis(player));
    }

    @Test
    void heartbeatCoalescesWhileDatabaseIsSlow() {
        tracker.joined(player);
        for (int i = 0; i < 20; i++) { advance(1000); tracker.flushAll(); }
        assertEquals(1, io.tasks.size());
        io.drain();
        tracker.flushAll(); io.drain();
        assertEquals(20000L, store.total(player));
    }

    private void advance(long millis) { nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)); }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable runnable) { tasks.add(runnable); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    private static final class FakeStore implements OnlineTimeTracker.Store {
        final Map<UUID, Long> elapsed = new HashMap<>();
        final Map<UUID, UUID> owners = new HashMap<>();
        final List<Report> reports = new ArrayList<>();
        boolean failNext;
        boolean failAfterCommit;
        public long record(UUID player, UUID session, long millis, long age) throws Exception {
            if (failNext) { failNext = false; throw new java.sql.SQLException("temporary unavailable"); }
            reports.add(new Report(session, millis, age));
            owners.put(session, player);
            elapsed.merge(session, millis, Math::max);
            if (failAfterCommit) { failAfterCommit = false; throw new java.sql.SQLException("response lost"); }
            return total(player);
        }
        public long total(UUID player) {
            return elapsed.entrySet().stream().filter(e -> player.equals(owners.get(e.getKey())))
                .mapToLong(Map.Entry::getValue).sum();
        }
    }
    private record Report(UUID session, long elapsed, long age) {}
}
