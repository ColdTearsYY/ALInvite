package com.alinvite.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 插件唯一的数据库/IO 线程池。
 * 所有 {@code CompletableFuture.supplyAsync / runAsync} 必须显式指定本池，
 * 禁止使用默认 ForkJoinPool.commonPool（低核数服务器上会退化为提交线程同步执行，
 * 且嵌套 join 存在饥饿/死锁风险）。
 */
public final class AsyncPool {

    private static final AtomicInteger COUNTER = new AtomicInteger(1);
    private static volatile ExecutorService pool;

    private AsyncPool() {
    }

    public static synchronized void init(int threads) {
        shutdown();
        int size = Math.max(2, threads);
        pool = Executors.newFixedThreadPool(size, new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "alinvite-io-" + index.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public static ExecutorService executor() {
        ExecutorService current = pool;
        if (current == null) {
            synchronized (AsyncPool.class) {
                if (pool == null) {
                    init(4);
                }
                current = pool;
            }
        }
        return current;
    }

    public static <T> CompletableFuture<T> supply(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor());
    }

    public static CompletableFuture<Void> run(Runnable task) {
        return CompletableFuture.runAsync(task, executor());
    }

    public static synchronized void shutdown() {
        ExecutorService current = pool;
        if (current != null) {
            current.shutdown();
            pool = null;
        }
    }
}
