package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps unbound players at their join location until they bind or the grace period expires. */
public final class BindingGateManager implements Listener {

    private record Session(Location anchor, long deadlineMillis, long nextReminderMillis) {
        Session withNextReminder(long nextReminder) {
            return new Session(anchor, deadlineMillis, nextReminder);
        }
    }

    private final ALInvite plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private int taskId = -1;

    public BindingGateManager(ALInvite plugin) {
        this.plugin = plugin;
        if (isEnabled()) {
            taskId = plugin.getScheduler().runGlobalTimer(this::tick, 20L, 20L);
        }
    }

    public boolean isEnabled() {
        return plugin.getConfigManager().getConfig().getBoolean("binding_gate.enabled", true);
    }

    public void lock(Player player) {
        lock(player, true);
    }

    public void lock(Player player, boolean notify) {
        if (!isEnabled()) {
            return;
        }
        int graceSeconds = Math.max(1, plugin.getConfigManager().getConfig()
            .getInt("binding_gate.grace_period_seconds", 60));
        int reminderSeconds = Math.max(1, plugin.getConfigManager().getConfig()
            .getInt("binding_gate.reminder_interval_seconds", 10));
        long now = System.currentTimeMillis();
        sessions.put(player.getUniqueId(), new Session(
            player.getLocation().clone(),
            now + graceSeconds * 1000L,
            now + reminderSeconds * 1000L));
        if (notify) {
            prompt(player);
        }
    }

    public void prompt(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage(message("binding_gate.join_prompt",
                "&e请输入邀请码~ &f/alinvite bind [邀请码]"));
        }
    }

    public void unlock(UUID uuid) {
        sessions.remove(uuid);
    }

    public boolean isLocked(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public void shutdown() {
        sessions.clear();
        if (taskId != -1) {
            plugin.getScheduler().cancel(taskId);
            taskId = -1;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        int reminderSeconds = Math.max(1, plugin.getConfigManager().getConfig()
            .getInt("binding_gate.reminder_interval_seconds", 10));
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            UUID uuid = entry.getKey();
            Session session = entry.getValue();
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                sessions.remove(uuid, session);
                continue;
            }
            if (now >= session.deadlineMillis()) {
                if (sessions.remove(uuid, session)) {
                    plugin.getScheduler().runAtPlayer(player, () ->
                        player.kickPlayer(ConfigManager.colorize(messageRaw(
                            "binding_gate.kick_message",
                            "&c请输入邀请码~ /alinvite bind [邀请码]"))));
                }
                continue;
            }
            if (now >= session.nextReminderMillis()) {
                Session updated = session.withNextReminder(now + reminderSeconds * 1000L);
                if (sessions.replace(uuid, session, updated)) {
                    plugin.getScheduler().runAtPlayer(player, () ->
                        player.sendMessage(message("binding_gate.reminder",
                            "&e请输入 /alinvite bind [邀请码] 绑定你的邀请码")));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()
                || from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ()) {
            Location anchor = session.anchor().clone();
            anchor.setYaw(to.getYaw());
            anchor.setPitch(to.getPitch());
            event.setTo(anchor);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        unlock(event.getPlayer().getUniqueId());
    }

    private String message(String path, String fallback) {
        return ConfigManager.colorize(messageRaw(path, fallback));
    }

    private String messageRaw(String path, String fallback) {
        String value = plugin.getConfigManager().getMessageRaw(path);
        return value == null || value.equals(path) ? fallback : value;
    }
}
