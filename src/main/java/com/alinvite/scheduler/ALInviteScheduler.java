package com.alinvite.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 全插件唯一的调度器包装类。
 * 所有 Bukkit 任务调度（打开菜单、异步取数、延迟动作、定时刷新、关闭清理）必须经过本类，
 * 业务代码中禁止出现任何 Bukkit.getScheduler() / entity.getScheduler() 裸调用。
 *
 * 四个调度域：
 *  - runGlobal*        与位置无关的全局逻辑（发命令、跨玩家状态更新、启动任务）
 *  - runAtPlayer*      GUI 主战场：打开菜单、回填数据、翻页、延迟动作。玩家退出后任务自动作废
 *  - runAsync*         数据库/HTTP/文件 IO。异步回调里绝不碰 Inventory / Player 状态
 *  - cancel / cancelAll 统一句柄登记与回收，onDisable 必调 cancelAll
 */
public final class ALInviteScheduler {

    private static final boolean FOLIA;
    private static final boolean MOHIST;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {
        }
        FOLIA = folia;
        MOHIST = detectMohist();
    }

    private final Plugin plugin;
    private final AtomicInteger nextTaskId = new AtomicInteger(1);
    private final Map<Integer, Object> handles = new ConcurrentHashMap<>();

    public ALInviteScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isFolia() {
        return FOLIA;
    }

    public boolean isMohist() {
        return MOHIST;
    }

    // ─── 全局同步域 ───

    public void runGlobal(Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runGlobalDelayed(Runnable task, long delayTicks) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), Math.max(1L, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks));
        }
    }

    /** 返回句柄可用于 cancel。 */
    public int runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        Object handle;
        if (FOLIA) {
            handle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(),
                    Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        } else {
            handle = Bukkit.getScheduler().runTaskTimer(plugin, task, Math.max(0L, delayTicks), Math.max(1L, periodTicks));
        }
        return register(handle);
    }

    // ─── 实体域（玩家） ───

    public void runAtPlayer(Player player, Runnable task) {
        runAtEntity(player, task);
    }

    public void runAtEntity(Entity entity, Runnable task) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, ignored -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAtPlayerDelayed(Player player, Runnable task, long delayTicks) {
        runAtEntityDelayed(player, task, delayTicks);
    }

    public void runAtEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, ignored -> task.run(), null, Math.max(1L, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks));
        }
    }

    /**
     * 在玩家线程执行并带回返回值（读权限、经济操作、发物品等必须切回实体线程的场景）。
     * 玩家退出时 future 以异常结束，不会悬挂。
     */
    public <T> CompletableFuture<T> supplyAtPlayer(Player player, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (FOLIA) {
            player.getScheduler().run(plugin, ignored -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }, () -> future.completeExceptionally(
                new java.util.concurrent.CancellationException("玩家已退出，实体任务未执行")));
        } else {
            runAtEntity(player, () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        }
        return future;
    }

    // ─── 异步域（只产出数据，不碰界面） ───

    public void runAsync(Runnable task) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runAsyncDelayed(Runnable task, long delayTicks) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> task.run(),
                    Math.max(0L, delayTicks) * 50L, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(0L, delayTicks));
        }
    }

    /** 返回句柄可用于 cancel。 */
    public int runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        Object handle;
        if (FOLIA) {
            handle = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> task.run(),
                    Math.max(0L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS);
        } else {
            handle = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, Math.max(0L, delayTicks), Math.max(1L, periodTicks));
        }
        return register(handle);
    }

    // ─── 任务管理 ───

    public void cancel(int taskId) {
        Object handle = handles.remove(taskId);
        if (handle == null) {
            return;
        }
        if (FOLIA) {
            ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) handle).cancel();
        } else {
            ((org.bukkit.scheduler.BukkitTask) handle).cancel();
        }
    }

    /** onDisable 必调：句柄逐个取消 + 后端全量兜底。 */
    public void cancelAll() {
        for (Object handle : handles.values()) {
            try {
                if (FOLIA) {
                    ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) handle).cancel();
                } else {
                    ((org.bukkit.scheduler.BukkitTask) handle).cancel();
                }
            } catch (Throwable ignored) {
            }
        }
        handles.clear();
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private int register(Object handle) {
        int id = nextTaskId.getAndIncrement();
        handles.put(id, handle);
        return id;
    }

    private static boolean detectMohist() {
        try {
            Class.forName("com.mohistmc.MohistMC");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        try {
            String name = Bukkit.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains("mohist")) {
                return true;
            }
            String version = Bukkit.getVersion();
            if (version != null && version.toLowerCase(Locale.ROOT).contains("mohist")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
