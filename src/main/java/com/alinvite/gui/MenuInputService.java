package com.alinvite.gui;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天输入服务（填写邀请码）。
 * 修复旧实现两个问题：超时有真实的延迟任务兜底（不再依赖玩家再说话触发）、
 * 玩家退出/onDisable 时统一清理状态。
 */
public class MenuInputService implements Listener {

    private final ALInvite plugin;
    private final Map<UUID, Long> inputDeadlines = new ConcurrentHashMap<>();

    public MenuInputService(ALInvite plugin) {
        this.plugin = plugin;
    }

    public void startCodeInput(Player player) {
        String usePerm = plugin.getConfigManager().getConfig()
            .getString("invite_code.use_permission", "alinvite.use");
        if (!player.hasPermission(usePerm)) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission", player));
            return;
        }

        int timeoutSeconds = plugin.getConfigManager().getConfig().getInt("input_dialog.timeout", 30);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        inputDeadlines.put(player.getUniqueId(), deadline);

        // 真实超时任务：到期时若输入仍未完成则提示超时；完成后 deadline 已被移除，任务自然空转
        UUID uuid = player.getUniqueId();
        plugin.getScheduler().runAtPlayerDelayed(player, () -> {
            Long pendingDeadline = inputDeadlines.get(uuid);
            if (pendingDeadline != null && pendingDeadline <= deadline && player.isOnline()) {
                inputDeadlines.remove(uuid);
                player.sendMessage(plugin.getConfigManager().getMessage("dialog.timeout", player));
            }
        }, timeoutSeconds * 20L + 20L);

        player.closeInventory();
        String cancelKey = plugin.getConfigManager().getConfig().getString("input_dialog.cancel_key", "Q");
        String prompt = plugin.getConfigManager().getMessage("dialog.prompt", player).replace("{cancel_key}", cancelKey);
        player.sendMessage(prompt);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Long deadline = inputDeadlines.get(player.getUniqueId());
        if (deadline == null) {
            return;
        }

        event.setCancelled(true);
        inputDeadlines.remove(player.getUniqueId());

        String cancelKey = plugin.getConfigManager().getConfig().getString("input_dialog.cancel_key", "Q");
        String code = event.getMessage().trim();

        if (code.equalsIgnoreCase(cancelKey)) {
            player.sendMessage(plugin.getConfigManager().getMessage("dialog.cancel", player));
            return;
        }
        if (code.isEmpty()) {
            player.sendMessage(ConfigManager.colorize(plugin.getConfigManager()
                .getMessageRaw("dialog.empty_code", player)));
            return;
        }

        plugin.getInviteManager().bindInviteCode(player, code).thenAccept(result -> {
            String message;
            if (result.success) {
                message = plugin.getConfigManager().getMessage("dialog.success", player);
            } else {
                message = switch (result.type) {
                    case NO_PERMISSION -> plugin.getConfigManager().getMessage("errors.no_permission", player);
                    case CODE_NOT_FOUND -> plugin.getConfigManager().getMessage("dialog.fail", player);
                    case ALREADY_USED -> plugin.getConfigManager().getMessage("errors.already_used", player);
                    case IP_LIMIT -> plugin.getConfigManager().getMessage("dialog.ip_limit", player);
                    case SELF_INVITE -> plugin.getConfigManager().getMessage("dialog.self_invite", player);
                    case VETERAN_CANNOT_BIND -> plugin.getConfigManager().getMessage("errors.veteran_cannot_bind", player);
                    case INVITER_LIMIT_REACHED -> plugin.getConfigManager().getMessage("errors.inviter_limit_reached", player);
                    default -> plugin.getConfigManager().getMessage("dialog.fail", player);
                };
            }
            final String finalMessage = message;
            plugin.getScheduler().runAtPlayer(player, () -> player.sendMessage(finalMessage));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void clear(UUID uuid) {
        inputDeadlines.remove(uuid);
    }

    public void clearAll() {
        inputDeadlines.clear();
    }
}
