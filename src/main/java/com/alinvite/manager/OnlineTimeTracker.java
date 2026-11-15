package com.alinvite.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Monotonic online sessions. Storage runs only on the supplied IO executor. */
public final class OnlineTimeTracker {
    public interface Store {
        long record(UUID player, UUID session, long elapsedMillis, long reportAgeMillis) throws Exception;
        long total(UUID player) throws Exception;
    }

    private final Store store;
    private final Executor executor;
    private final LongSupplier nanoTime;
    private final Object lock = new Object();
    private final Map<UUID, Session> active = new HashMap<>();
    private final Map<UUID, Map<UUID, Session>> pending = new HashMap<>();
    private final Map<UUID, CompletableFuture<Long>> queues = new HashMap<>();
    private final LinkedHashMap<UUID, Long> cached = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    public OnlineTimeTracker(Store store, Executor executor, LongSupplier nanoTime) {
        this.store = store;
        this.executor = executor;
        this.nanoTime = nanoTime;
    }

    public CompletableFuture<Long> joined(UUID player) {
        synchronized (lock) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("计时器已关闭"));
            if (!active.containsKey(player)) {
                Session session = new Session(UUID.randomUUID(), nanoTime.getAsLong());
                active.put(player, session);
                pending.computeIfAbsent(player, ignored -> new LinkedHashMap<>()).put(session.id, session);
            }
            return refresh(player);
        }
    }

    public CompletableFuture<Long> left(UUID player) {
        synchronized (lock) {
            Session session = active.remove(player);
            if (session != null) session.stop(nanoTime.getAsLong());
            return refresh(player);
        }
    }

    /** Flushes a snapshot before returning a persisted total; no uncommitted local estimates. */
    public CompletableFuture<Long> refresh(UUID player) {
        synchronized (lock) {
            long now = nanoTime.getAsLong();
            List<Sample> samples = new ArrayList<>();
            Map<UUID, Session> sessions = pending.get(player);
            if (sessions != null) {
                for (Session session : sessions.values()) samples.add(session.sample(now));
            }
            CompletableFuture<Long> previous = queues.getOrDefault(player,
                CompletableFuture.completedFuture(0L));
            CompletableFuture<Long> next = previous.handle((value, error) -> null).thenApplyAsync(ignored -> {
                try {
                    for (Sample sample : samples) {
                        long age = millisSince(sample.capturedNanos, nanoTime.getAsLong());
                        store.record(player, sample.sessionId, sample.elapsedMillis, age);
                        if (sample.finished) {
                            synchronized (lock) {
                                Map<UUID, Session> remaining = pending.get(player);
                                if (remaining != null) {
                                    remaining.remove(sample.sessionId);
                                    if (remaining.isEmpty()) pending.remove(player);
                                }
                            }
                        }
                    }
                    long total = store.total(player);
                    synchronized (lock) {
                        cached.put(player, Math.max(total, cached.getOrDefault(player, 0L)));
                        if (cached.size() > 10000) cached.remove(cached.keySet().iterator().next());
                    }
                    return total;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executor);
            queues.put(player, next);
            next.whenComplete((value, error) -> {
                synchronized (lock) { queues.remove(player, next); }
            });
            return next;
        }
    }

    /** Cached committed value for UI/PAPI. A cold read schedules IO and returns -1 until ready. */
    public long cachedMillis(UUID player) {
        synchronized (lock) {
            Long value = cached.get(player);
            if (value != null) return value;
            if (!queues.containsKey(player) && !closed) refresh(player).exceptionally(error -> null);
            return -1;
        }
    }

    /** Retry active and failed quit reports even when no players remain online. */
    public CompletableFuture<Void> flushAll() {
        synchronized (lock) {
            List<CompletableFuture<?>> writes = new ArrayList<>(queues.values());
            for (UUID player : new ArrayList<>(pending.keySet())) {
                if (!queues.containsKey(player)) writes.add(refresh(player));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        }
    }

    public CompletableFuture<Void> close() {
        synchronized (lock) {
            closed = true;
            long now = nanoTime.getAsLong();
            for (Session session : active.values()) session.stop(now);
            active.clear();
            // Always append final snapshots behind in-flight writes; do not omit shutdown tails.
            List<CompletableFuture<?>> writes = new ArrayList<>(queues.values());
            for (UUID player : new ArrayList<>(pending.keySet())) writes.add(refresh(player));
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        }
    }

    static long millisSince(long start, long end) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, end - start));
    }

    private static final class Session {
        final UUID id;
        final long startedNanos;
        boolean finished;
        long stoppedNanos;

        Session(UUID id, long startedNanos) {
            this.id = id;
            this.startedNanos = startedNanos;
        }
        void stop(long now) {
            if (!finished) {
                finished = true;
                stoppedNanos = now;
            }
        }
        Sample sample(long now) {
            long captured = finished ? stoppedNanos : now;
            return new Sample(id, millisSince(startedNanos, captured), captured, finished);
        }
    }

    private record Sample(UUID sessionId, long elapsedMillis, long capturedNanos, boolean finished) {}
}
