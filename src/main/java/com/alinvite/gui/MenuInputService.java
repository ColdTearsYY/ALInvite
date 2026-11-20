package com.alinvite.gui;

import com.alinvite.ALInvite;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Free-text entry without command syntax. One expiring input belongs to one login and navigation. */
public class MenuInputService implements Listener {
    private record Input(Player player, boolean invitee, long deadline, Object navigation) {}
    private final ALInvite plugin;
    private final Map<UUID, Input> inputs = new ConcurrentHashMap<>();

    public MenuInputService(ALInvite plugin) { this.plugin = plugin; }

    public void startInviteeInput(Player player) { start(player, true); }
    public void startCodeInput(Player player) { start(player, false); }

    private boolean allowed(Player player, boolean invitee) {
        String permission = invitee ? "alinvite.use" : plugin.getConfigManager().getConfig()
            .getString("invite_code.use_permission", "alinvite.use");
        return player.hasPermission(permission) && (!invitee || !plugin.getBindingGateManager().isLocked(player.getUniqueId()));
    }

    private void start(Player player, boolean invitee) {
        if (!allowed(player, invitee)) {
            message(player, invitee ? "commands.add.no_permission" : "errors.no_permission");
            return;
        }
        int timeout = Math.max(1, plugin.getConfigManager().getConfig().getInt("input_dialog.timeout", 30));
        UUID uuid = player.getUniqueId();
        player.closeInventory();
        Object navigation = plugin.getMenuManager().getSessions().beginLoad(uuid);
        Input input = new Input(player, invitee, System.currentTimeMillis() + timeout * 1000L, navigation);
        inputs.put(uuid, input);
        prompt(player, input);
        plugin.getScheduler().runAtPlayerDelayed(player, () -> {
            if (inputs.remove(uuid, input) && current(player, input)) {
                message(player, "dialog.timeout");
                returnToMenu(player, input);
            }
        }, timeout * 20L + 1L);
    }

    private String cancelKey() { return plugin.getConfigManager().getConfig().getString("input_dialog.cancel_key", "Q"); }
    private boolean current(Player player, Input input) {
        return player == input.player() && player.isOnline()
            && plugin.getMenuManager().getSessions().isLatestLoad(player.getUniqueId(), input.navigation());
    }
    private void prompt(Player player, Input input) {
        player.sendMessage(plugin.getConfigManager().getMessage(input.invitee() ? "pending_menu.input_prompt" : "dialog.prompt", player)
            .replace("{cancel_key}", cancelKey()));
    }
    private void returnToMenu(Player player, Input input) {
        if (!current(player, input)) return;
        if (input.invitee() && player.hasPermission("alinvite.use")) plugin.getMenuManager().openPendingInvitesMenu(player);
        else plugin.getMenuManager().openMainMenu(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Input input = inputs.get(player.getUniqueId());
        if (input == null || input.player() != player) return;
        event.setCancelled(true);
        String value = event.getMessage().trim();
        plugin.getScheduler().runAtPlayer(player, () -> {
            if (inputs.get(player.getUniqueId()) != input || !current(player, input)) return;
            if (System.currentTimeMillis() >= input.deadline()) {
                inputs.remove(player.getUniqueId(), input);
                message(player, "dialog.timeout"); returnToMenu(player, input); return;
            }
            if (value.equalsIgnoreCase(cancelKey())) {
                inputs.remove(player.getUniqueId(), input);
                message(player, "dialog.cancel"); returnToMenu(player, input); return;
            }
            if (!allowed(player, input.invitee())) {
                inputs.remove(player.getUniqueId(), input);
                message(player, "errors.no_permission"); returnToMenu(player, input); return;
            }
            if (value.isEmpty() || input.invitee() && !value.matches("[A-Za-z0-9_]{3,16}")) {
                message(player, input.invitee() ? "commands.add.invalid_name" : "dialog.empty_code");
                prompt(player, input);
                return;
            }
            if (!inputs.remove(player.getUniqueId(), input)) return;
            if (input.invitee()) {
                plugin.getCommandHandler().createPreInvite(player, value, true, () -> returnToMenu(player, input));
                return;
            }
            plugin.getInviteManager().bindInviteCode(player, value).whenComplete((result, error) ->
                plugin.getScheduler().runAtPlayer(player, () -> {
                    if (!current(player, input)) return;
                    String path = error != null ? "dialog.fail" : result.success ? "dialog.success" : switch (result.type) {
                        case NO_PERMISSION -> "errors.no_permission";
                        case CODE_NOT_FOUND -> "dialog.fail";
                        case ALREADY_USED -> "errors.already_used";
                        case IP_LIMIT -> "dialog.ip_limit";
                        case SELF_INVITE -> "dialog.self_invite";
                        case VETERAN_CANNOT_BIND -> "errors.veteran_cannot_bind";
                        case INVITER_LIMIT_REACHED -> "errors.inviter_limit_reached";
                        case QUOTA_EXHAUSTED -> "errors.invite_quota_exhausted";
                        default -> "dialog.fail";
                    };
                    message(player, path);
                    returnToMenu(player, input);
                }));
        });
    }

    private void message(Player player, String key) { player.sendMessage(plugin.getConfigManager().getMessage(key, player)); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer().getUniqueId()); }
    public void clear(UUID uuid) { inputs.remove(uuid); }
    public void clearAll() { inputs.clear(); }
}
