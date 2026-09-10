package com.alinvite.gui;

import com.alinvite.ALInvite;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumSet;
import java.util.Set;

/**
 * 菜单点击监听：Click/Drag/Close/Quit 四件套。
 *  - 本插件菜单内一律 setCancelled(true)（危险点击类型在放行判断之前先挡）；
 *  - 从物品 PDC 读动作串 → 冷却防抖 → 注册表分发；
 *  - Close（MONITOR）仅清除跟踪的就是该界面时的会话（翻页/切菜单不误伤新会话）。
 */
public class MenuClickListener implements Listener {

    private static final Set<ClickType> DANGEROUS_CLICKS = EnumSet.of(
            ClickType.NUMBER_KEY,
            ClickType.DOUBLE_CLICK,
            ClickType.MIDDLE,
            ClickType.DROP,
            ClickType.CONTROL_DROP,
            ClickType.SWAP_OFFHAND,
            ClickType.WINDOW_BORDER_LEFT,
            ClickType.WINDOW_BORDER_RIGHT);

    private final ALInvite plugin;
    private final MenuManager menuManager;
    private final ActionPdc pdc;
    private final MenuActionExecutor executor;
    private final ButtonCooldowns cooldowns;

    public MenuClickListener(ALInvite plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.pdc = menuManager.getPdc();
        this.executor = new MenuActionExecutor(plugin);
        this.cooldowns = menuManager.getCooldowns();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof InviteMenuHolder)) {
            return;
        }

        // 先取消一切点击，防止物品移动/拿取
        event.setCancelled(true);

        ClickType clickType = event.getClick();
        if (DANGEROUS_CLICKS.contains(clickType)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }

        if (menuManager.getSessions().currentMenu(player.getUniqueId(), top) == null) {
            menuManager.getSessions().clearAll(player.getUniqueId());
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }

        String clickKey = clickKeyOf(clickType);
        if (clickKey == null) {
            return;
        }
        String action = pdc.read(meta, clickKey);
        if (action == null || action.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = Math.max(0L, Math.round(
            plugin.getConfig().getDouble("performance.button_cooldown_seconds", 0.5D) * 1000D));
        if (cooldowns.isCoolingDown(player.getUniqueId(), menuManager.currentMenuName(player), rawSlot, now, cooldownMillis)) {
            return;
        }
        cooldowns.mark(player.getUniqueId(), menuManager.currentMenuName(player), rawSlot, now);

        executor.execute(player, action, value -> menuManager.getRegistry().dispatch(player, value));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof InviteMenuHolder) {
            event.setCancelled(true);
            if (menuManager.getSessions().currentMenu(player.getUniqueId(), top) == null) {
                menuManager.getSessions().clearAll(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        menuManager.getSessions().clear(player.getUniqueId(), event.getInventory());
        cooldowns.clear(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        menuManager.getSessions().clearAll(player.getUniqueId());
        menuManager.getPages().clear(player.getUniqueId());
        cooldowns.clear(player.getUniqueId());
        menuManager.clearPlayerState(player.getUniqueId());
    }

    private String clickKeyOf(ClickType clickType) {
        return switch (clickType) {
            case LEFT -> "left";
            case RIGHT -> "right";
            case SHIFT_LEFT -> "shift_left";
            case SHIFT_RIGHT -> "shift_right";
            default -> null;
        };
    }
}
