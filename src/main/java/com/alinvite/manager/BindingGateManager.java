package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps unbound players frozen (spectator + anchored) until they bind or the grace period expires. */
public final class BindingGateManager implements Listener {

    private static final Set<String> ALLOWED_COMMAND_ROOTS = Set.of("alinvite", "ali", "invite");

    private record Session(Location anchor, long deadlineMillis, long nextReminderMillis) {
        Session withNextReminder(long nextReminder) {
            return new Session(anchor, deadlineMillis, nextReminder);
        }
    }

    private final ALInvite plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> restoreModes = new ConcurrentHashMap<>();
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

    private boolean spectatorLockEnabled() {
        return plugin.getConfigManager().getConfig().getBoolean("binding_gate.spectator_lock", true);
    }

    private boolean blockCommandsEnabled() {
        return plugin.getConfigManager().getConfig().getBoolean("binding_gate.block_commands", true);
    }

    private boolean blockChatEnabled() {
        return plugin.getConfigManager().getConfig().getBoolean("binding_gate.block_chat", true);
    }

    private boolean positionLockEnabled() {
        return plugin.getConfigManager().getConfig().getBoolean("binding_gate.position_lock", true);
    }

    private boolean blockInteractionEnabled() {
        return plugin.getConfigManager().getConfig().getBoolean("binding_gate.block_interaction", true);
    }

    private GameMode fallbackGameMode() {
        String raw = plugin.getConfigManager().getConfig()
            .getString("binding_gate.restore_gamemode", "SURVIVAL");
        try {
            return GameMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return GameMode.SURVIVAL;
        }
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

    /** Prompts the player and forces spectator mode so damage/interaction cannot occur. */
    public void enforceLock(Player player) {
        prompt(player);
        enforceSpectator(player);
    }

    private void enforceSpectator(Player player) {
        if (!isEnabled() || !spectatorLockEnabled()) {
            return;
        }
        if (!sessions.containsKey(player.getUniqueId())) {
            return;
        }
        GameMode current = player.getGameMode();
        GameMode restore = (current == GameMode.SPECTATOR) ? fallbackGameMode() : current;
        restoreModes.put(player.getUniqueId(), restore);
        if (current != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
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
        GameMode restore = restoreModes.remove(uuid);
        if (restore == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null && player.isOnline() && player.getGameMode() == GameMode.SPECTATOR) {
            plugin.getScheduler().runAtPlayer(player, () -> player.setGameMode(restore));
        }
    }

    public boolean isLocked(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public void shutdown() {
        sessions.clear();
        restoreModes.clear();
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
                restoreModes.remove(uuid);
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
        if (!positionLockEnabled()) {
            return;
        }
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
        if (positionLockEnabled() && sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        GameMode restore = restoreModes.remove(uuid);
        if (restore != null && player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(restore);
        }
        sessions.remove(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!blockCommandsEnabled() || !isLocked(event.getPlayer().getUniqueId())) {
            return;
        }
        if (!ALLOWED_COMMAND_ROOTS.contains(commandRoot(event.getMessage()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(message("binding_gate.command_blocked",
                "&c绑定邀请码之前只能使用 &f/alinvite bind [邀请码]"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (blockChatEnabled() && isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(message("binding_gate.chat_blocked",
                "&c绑定邀请码之前无法聊天，请先 /alinvite bind [邀请码]"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (blockInteractionEnabled() && event.getPlayer() instanceof Player player
                && isLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (blockInteractionEnabled() && event.getWhoClicked() instanceof Player player
                && isLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (blockInteractionEnabled() && isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (blockInteractionEnabled() && isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private static String commandRoot(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }
        String trimmed = rawMessage.startsWith("/") ? rawMessage.substring(1) : rawMessage;
        trimmed = trimmed.trim();
        int space = trimmed.indexOf(' ');
        String root = (space == -1) ? trimmed : trimmed.substring(0, space);
        int colon = root.indexOf(':');
        if (colon != -1) {
            root = root.substring(colon + 1);
        }
        return root.toLowerCase(Locale.ROOT);
    }

    private String message(String path, String fallback) {
        return ConfigManager.colorize(messageRaw(path, fallback));
    }

    private String messageRaw(String path, String fallback) {
        String value = plugin.getConfigManager().getMessageRaw(path);
        return value == null || value.equals(path) ? fallback : value;
    }
}
