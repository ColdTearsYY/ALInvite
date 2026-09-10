package com.alinvite.gui;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 菜单动作注册表：新动作 = 注册一条，禁止巨型 switch。
 * 通用 DSL（sound:/message:/console:/player:/close 别名）内置。
 */
public class MenuActionRegistry {

    private final Map<String, BiConsumer<Player, String>> handlers = new HashMap<>();
    private final ALInvite plugin;

    public MenuActionRegistry(ALInvite plugin) {
        this.plugin = plugin;
        registerDefaults();
    }

    public void register(String type, BiConsumer<Player, String> handler) {
        handlers.put(type.toLowerCase(Locale.ROOT), handler);
    }

    /** 解析并执行单个动作串（type:value）。 */
    public void dispatch(Player player, String action) {
        String normalized = action == null ? "" : action.trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (normalized.regionMatches(true, 0, "action:", 0, 7)) {
            normalized = normalized.substring(7).trim();
            if (normalized.isEmpty()) {
                return;
            }
        }
        int separator = normalized.indexOf(':');
        String type = separator < 0 ? normalized.toLowerCase(Locale.ROOT)
                : normalized.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        String value = separator < 0 ? "" : normalized.substring(separator + 1).trim();

        BiConsumer<Player, String> handler = handlers.get(type);
        if (handler != null) {
            handler.accept(player, value);
            return;
        }
        player.sendMessage(ConfigManager.colorize("&c未配置的菜单动作: " + type));
    }

    private void registerDefaults() {
        register("open_main", (player, value) -> plugin.getMenuManager().openMainMenu(player));
        register("open_veteran", (player, value) -> plugin.getMenuManager().openVeteranMenu(player));
        register("open_shop", (player, value) -> plugin.getMenuManager().openShopMenu(player));
        register("open_rebate_history", (player, value) -> plugin.getMenuManager().openRebateHistoryMenu(player));
        register("back_veteran", (player, value) -> plugin.getMenuManager().openVeteranMenu(player));
        // back = 按固定层级返回上一级（主菜单 → 邀请中心 → 礼包商店）
        register("back", (player, value) -> {
            String parent = plugin.getMenuManager().getParentMenu(
                plugin.getMenuManager().currentMenuName(player));
            if (parent != null) {
                plugin.getMenuManager().openByName(player, parent);
            }
        });

        register("claim_rebate", (player, value) -> plugin.getPointsRebateManager().claimRebate(player));
        register("toggle_rebate_view", (player, value) -> plugin.getMenuManager().toggleRebateView(player));

        register("close", (player, value) -> player.closeInventory());
        register("silent-close", (player, value) -> player.closeInventory());
        register("force-close", (player, value) -> player.closeInventory());

        register("input_code", (player, value) -> plugin.getMenuManager().getInputService().startCodeInput(player));

        register("prev_page", (player, value) -> changePage(player, -1));
        register("next_page", (player, value) -> changePage(player, 1));

        register("claim_milestone", (player, value) -> {
            try {
                plugin.getMilestoneManager().claimFromMenu(player, Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("无效的里程碑动作取值: " + value);
            }
        });

        register("buy_gift", (player, value) ->
            plugin.getGiftManager().purchaseFromMenu(player, value, true));

        register("sound", (player, value) -> playSound(player, value));
        register("message", (player, value) ->
            player.sendMessage(ConfigManager.colorize(value.replace("%player_name%", player.getName()))));
        register("console", (player, value) ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                value.replace("%player_name%", player.getName())));
        register("player", (player, value) ->
            player.performCommand(value.replace("%player_name%", player.getName())));
    }

    private void changePage(Player player, int delta) {
        MenuSessionStore.Session session = plugin.getMenuManager().getSessions().get(player.getUniqueId());
        if (session == null) {
            return;
        }
        plugin.getMenuManager().changePage(player, session.menuName(), delta);
    }

    /** value 格式: SOUND_NAME 或 SOUND_NAME-volume-pitch */
    private void playSound(Player player, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String[] parts = value.split("-");
        try {
            Sound sound = Sound.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1.0f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1.0f;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception e) {
            plugin.getLogger().warning("无效的音效配置: " + value);
        }
    }
}
