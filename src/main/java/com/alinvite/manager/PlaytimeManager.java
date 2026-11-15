package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Built-in, shared-database online time; no PAPI, statistics plugin or Redis dependency. */
public final class PlaytimeManager implements Listener {
    private final ALInvite plugin;
    private final OnlineTimeTracker tracker;
    private int taskId = -1;

    public PlaytimeManager(ALInvite plugin) {
        this.plugin = plugin;
        this.tracker = new OnlineTimeTracker(new OnlineTimeTracker.Store() {
            @Override
            public long record(UUID player, UUID session, long elapsed, long age) throws Exception {
                return plugin.getDatabaseManager().getPlaytimeDao().record(player, session, elapsed, age);
            }
            @Override
            public long total(UUID player) throws Exception {
                return plugin.getDatabaseManager().getPlaytimeDao().getTotalMillis(player);
            }
        }, AsyncPool.executor(), System::nanoTime);
    }

    /** Also used on config reload: retain the same sessions and monotonic clock origins. */
    public void start() {
        if (taskId != -1) plugin.getScheduler().cancel(taskId);
        long seconds = Math.max(5L, Math.min(300L, plugin.getConfigManager().getConfig()
            .getLong("playtime_tracking.save_interval", 30L)));
        taskId = plugin.getScheduler().runGlobalTimer(() -> observe(tracker.flushAll()), 20L, seconds * 20L);
        plugin.getScheduler().runGlobal(() -> Bukkit.getOnlinePlayers().forEach(player ->
            observe(tracker.joined(player.getUniqueId()))));
        plugin.getLogger().info("内置在线时长已启用，保存间隔 " + seconds + " 秒；跨服共享数据库按 UUID 去重累计");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        observe(tracker.joined(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        observe(tracker.left(event.getPlayer().getUniqueId()));
    }

    public CompletableFuture<Long> refresh(UUID player) { return tracker.refresh(player); }
    public long cachedMillis(UUID player) { return tracker.cachedMillis(player); }

    /** Only called during plugin disable; bounded wait before IO pool/database are closed. */
    public void shutdown() {
        if (taskId != -1) plugin.getScheduler().cancel(taskId);
        taskId = -1;
        long seconds = Math.max(1L, Math.min(30L, plugin.getConfigManager().getConfig()
            .getLong("playtime_tracking.shutdown_wait_seconds", 10L)));
        try {
            tracker.close().get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("在线时长停服保存被中断，未确认保存的尾段可能丢失");
        } catch (Exception error) {
            plugin.getLogger().warning("在线时长停服保存未完成，未确认保存的尾段可能丢失: " + error.getMessage());
        }
    }

    private void observe(CompletableFuture<?> future) {
        future.exceptionally(error -> {
            plugin.getLogger().warning("在线时长保存失败，保留会话等待重试: " + error.getMessage());
            return null;
        });
    }

    /** Shared by native menus and optional PlaceholderAPI expansion. */
    public String placeholder(UUID player, String key) {
        long millis = cachedMillis(player);
        if (millis < 0) return "";
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "playtime_seconds" -> Long.toString(millis / 1000L);
            case "playtime_minutes" -> Long.toString(millis / 60000L);
            case "playtime_hours" -> Long.toString(millis / 3600000L);
            case "playtime" -> format(millis);
            default -> null;
        };
    }

    public static String format(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return (seconds / 3600L) + "h " + (seconds / 60L % 60L) + "m " + (seconds % 60L) + "s";
    }
}
