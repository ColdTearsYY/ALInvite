package com.alinvite.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class SchedulerUtils {

    private static Boolean IS_FOLIA = null;

    public static boolean isFolia() {
        if (IS_FOLIA == null) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                IS_FOLIA = true;
            } catch (ClassNotFoundException e) {
                IS_FOLIA = false;
            }
        }
        return IS_FOLIA;
    }

    public static <T> T runTaskSupplied(Plugin plugin, Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) {
            return supplier.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        runTask(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.join();
    }

    public static <T> T runTaskSupplied(Plugin plugin, Player player, Supplier<T> supplier) {
        if (!isFolia() && Bukkit.isPrimaryThread()) {
            return supplier.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        runTask(plugin, player, () -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.join();
    }

    public static void runTask(Plugin plugin, Runnable runnable) {
        if (isFolia()) {
            runTaskFolia(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static void runTask(Plugin plugin, Player player, Runnable runnable) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
            return;
        }

        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            scheduler.getClass()
                .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class)
                .invoke(scheduler, plugin, runnable, null, 1L);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to schedule Folia player task: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    public static void runTaskLater(Plugin plugin, Runnable runnable, long delay) {
        if (isFolia()) {
            if (delay <= 0) {
                runTaskFolia(plugin, runnable);
            } else {
                runTaskLaterFolia(plugin, runnable, delay);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        }
    }

    public static void runTaskAsynchronously(Plugin plugin, Runnable runnable) {
        if (isFolia()) {
            runTaskAsyncFolia(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        }
    }

    public static void runTaskTimer(Plugin plugin, Runnable runnable, long delay, long period) {
        if (isFolia()) {
            runTaskTimerFolia(plugin, runnable, delay, period);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, runnable, delay, period);
        }
    }

    public static void runTaskLaterAsync(Plugin plugin, Runnable runnable, long delay) {
        if (isFolia()) {
            runTaskLaterFolia(plugin, runnable, delay);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delay);
        }
    }

    public static void runTaskTimerAsync(Plugin plugin, Runnable runnable, long delay, long period) {
        if (isFolia()) {
            // Folia不支持异步定时任务，使用同步定时任务替代
            runTaskTimer(plugin, runnable, delay, period);
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delay, period);
        }
    }

    // Folia专用方法 - 完全避免传统调度器
    private static void runTaskFolia(Plugin plugin, Runnable runnable) {
        try {
            // 直接在主线程执行
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
            } else {
                // 使用Folia的GlobalRegionScheduler
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                scheduler.getClass().getMethod("run", Plugin.class, java.util.function.Consumer.class)
                    .invoke(scheduler, plugin, (java.util.function.Consumer<Object>) task -> runnable.run());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Folia任务执行失败: " + e.getMessage());
            // 备用方案：直接在新线程执行
        }
    }

    private static void runTaskLaterFolia(Plugin plugin, Runnable runnable, long delay) {
        if (delay <= 0) {
            runTaskFolia(plugin, runnable);
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            scheduler.getClass().getMethod("runDelayed", Plugin.class, java.util.function.Consumer.class, long.class)
                .invoke(scheduler, plugin, (java.util.function.Consumer<Object>) task -> runnable.run(), delay);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnsupportedOperationException) {
                plugin.getLogger().warning("Folia调度器不支持该操作，使用异步回退方案");
                runTaskLaterAsync(plugin, runnable, delay);
            } else {
                plugin.getLogger().warning("Folia延迟任务执行失败: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
                fallbackTaskLater(plugin, runnable, delay);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Folia延迟任务反射调用失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            fallbackTaskLater(plugin, runnable, delay);
        }
    }

    private static void fallbackTaskLater(Plugin plugin, Runnable runnable, long delay) {
        new Thread(() -> {
            try {
                Thread.sleep(delay * 50);
                runnable.run();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private static void runTaskAsyncFolia(Plugin plugin, Runnable runnable) {
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            scheduler.getClass().getMethod("runNow", Plugin.class, java.util.function.Consumer.class)
                .invoke(scheduler, plugin, (java.util.function.Consumer<Object>) task -> runnable.run());
        } catch (Exception e) {
            plugin.getLogger().warning("Folia异步任务执行失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            new Thread(runnable).start();
        }
    }

    private static void runTaskTimerFolia(Plugin plugin, Runnable runnable, long delay, long period) {
        runTaskLaterFolia(plugin, () -> {
            try {
                runnable.run();
            } catch (Exception e) {
                plugin.getLogger().warning("Folia定时任务执行异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            runTaskTimerFolia(plugin, runnable, period, period);
        }, delay);
    }
}
