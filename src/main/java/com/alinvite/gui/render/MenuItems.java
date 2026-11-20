package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.MenuItem;
import com.alinvite.utils.ItemUtil;
import com.alinvite.utils.PluginItems;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * MenuItem + RenderContext → ItemStack 的构建桥。
 * 动作串写进 PDC；材质支持 "{material}"（动态取礼包材质）等运行时替换。
 */
public final class MenuItems {

    private MenuItems() {
    }

    /**
     * 构建物品。material 支持：
     *  - 原版材质名（非法值降级 STONE）
     *  - 'AIR'：返回 null，槽位保持空白
     *  - '{material}'：动态取上下文中的礼包材质
     *  - 插件物品前缀：'ce:命名空间:ID'、'ia:ID'、'oraxen:ID'（需安装对应插件）
     */
    public static ItemStack build(ALInvite plugin, ActionPdc pdc, MenuItem item,
                                  MenuItem.StateStyle state, RenderContext context) {
        String materialSpec = state != null && state.hasMaterial() ? state.getMaterial() : item.getMaterial();
        ItemStack stack = createStack(materialSpec, context);
        if (stack == null) {
            return null; // AIR：槽位留空
        }

        String rawName = state != null && state.hasName() ? state.getName() : item.getName();
        String name = MenuText.render(rawName, context);
        List<String> loreSource = state != null && state.hasLore() ? state.getLore() : item.getLore();
        if (context.strings().containsKey("quota_capacity")) {
            loreSource = loreSource.stream().filter(line -> !line.matches("(?i).*/(?:alinvite|travel|ali|invite) (?:status|pending|deliveries)\\s*$")).toList();
        }
        List<String> lore = new java.util.ArrayList<>(MenuText.renderLore(
            InviteCodeMenuSupport.upgradeLore(loreSource, context), context));
        String codesTrigger = context.lists().containsKey("available_invite_codes")
            ? InviteCodeMenuSupport.entryTrigger(item) : null;
        if (codesTrigger != null) lore.add(plugin.getConfigManager().getMessageRaw("codes_menu.entry_" + codesTrigger));

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(ConfigManager.colorize(name));
            }
            if (!lore.isEmpty()) {
                meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
            }
            int cmd = state != null && state.hasCustomModelData() ? state.getCustomModelData() : item.getCustomModelData();
            if (cmd > 0) {
                meta.setCustomModelData(cmd);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.getTriggers().forEach((trigger, actions) -> {
                var rendered = MenuText.renderActionList(actions, context);
                if ("true".equals(context.strings().get("bind_complete"))) {
                    rendered = rendered.stream().filter(action -> !action.replaceFirst("(?i)^action:", "").equalsIgnoreCase("input_code")).toList();
                }
                pdc.write(meta, trigger, rendered);
            });
            if (codesTrigger != null) pdc.write(meta, codesTrigger, List.of("open_codes"));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Fallback for legacy menus whose old code button already uses every gesture. */
    public static void ensureCodesEntry(ALInvite plugin, ActionPdc pdc,
                                        org.bukkit.inventory.Inventory inventory, RenderContext context) {
        if (!context.lists().containsKey("available_invite_codes")) return;
        var gestures = List.of("right", "shift_right", "shift_left", "left");
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || !stack.hasItemMeta()) continue;
            for (String gesture : gestures) {
                String actions = pdc.read(stack.getItemMeta(), gesture);
                if (actions != null && java.util.Arrays.stream(actions.split(ActionPdc.SEPARATOR))
                        .map(String::trim).anyMatch(action -> action.equalsIgnoreCase("open_codes")
                            || action.equalsIgnoreCase("action:open_codes"))) return;
            }
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !stack.hasItemMeta()) continue;
            ItemMeta meta = stack.getItemMeta();
            for (String gesture : gestures) {
                if (pdc.read(meta, gesture) != null) continue;
                var lore = new java.util.ArrayList<>(meta.hasLore() ? meta.getLore() : List.<String>of());
                lore.add(ConfigManager.colorize(context.strings().get("invite_codes_summary")));
                lore.add(plugin.getConfigManager().getMessageRaw("codes_menu.entry_" + gesture));
                meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
                pdc.write(meta, gesture, List.of("open_codes"));
                stack.setItemMeta(meta);
                inventory.setItem(slot, stack);
                return;
            }
        }
        plugin.getLogger().warning("自定义菜单所有物品的点击方式均已占用，请配置 open_codes 邀请码列表入口。");
    }

    /** Add missing main-menu features without rewriting custom files or replacing configured actions. */
    public static void ensureMainEntry(ALInvite plugin, ActionPdc pdc, com.alinvite.gui.MenuConfig config,
                                       org.bukkit.inventory.Inventory inventory, MenuItem entry, RenderContext context) {
        if (entry == null) return;
        var gestures = List.of("left", "right", "shift_left", "shift_right");
        String action = entry.getTriggerActions("left").stream().findFirst().orElse("");
        if (hasAction(pdc, inventory, action)) return;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            char key = config.getShape().get(slot / 9).charAt(slot % 9);
            var original = config.getItems().get(String.valueOf(key));
            ItemStack stack = inventory.getItem(slot);
            boolean decoration = key == '#' && original != null && original.getName().isBlank()
                && original.getLore().isEmpty() && original.getCustomModelData() == 0;
            if (!(key == ' ' && stack == null) && !decoration) continue;
            if (stack != null && gestures.stream().anyMatch(gesture -> pdc.read(stack.getItemMeta(), gesture) != null)) continue;
            inventory.setItem(slot, build(plugin, pdc, entry, null, context));
            return;
        }
        // Fully custom layouts can use an unused gesture; never overwrite their existing triggers.
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || !stack.hasItemMeta()) continue;
            ItemMeta meta = stack.getItemMeta();
            for (String gesture : gestures) {
                if (pdc.read(meta, gesture) != null) continue;
                var lore = new java.util.ArrayList<>(meta.hasLore() ? meta.getLore() : List.<String>of());
                lore.add(ConfigManager.colorize(plugin.getConfigManager().getMessageRaw("player_menu.gesture." + gesture)
                    + " → " + entry.getName()));
                meta.setLore(lore);
                pdc.write(meta, gesture, List.of(action));
                stack.setItemMeta(meta);
                return;
            }
        }
        plugin.getLogger().warning("自定义主菜单无可用入口，请配置菜单动作: " + action);
    }

    private static boolean hasAction(ActionPdc pdc, org.bukkit.inventory.Inventory inventory, String wanted) {
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || !stack.hasItemMeta()) continue;
            for (String gesture : List.of("left", "right", "shift_left", "shift_right")) {
                String encoded = pdc.read(stack.getItemMeta(), gesture);
                if (encoded == null) continue;
                if (java.util.Arrays.stream(encoded.split(ActionPdc.SEPARATOR)).map(String::trim)
                        .map(action -> action.replaceFirst("(?i)^action:", ""))
                        .anyMatch(action -> action.equalsIgnoreCase(wanted))) return true;
            }
        }
        return false;
    }
    private static ItemStack createStack(String spec, RenderContext context) {
        if (spec == null || spec.isBlank()) {
            return new ItemStack(Material.STONE);
        }
        if (spec.equalsIgnoreCase("{material}")) {
            spec = context.strings().getOrDefault("gift_material", "STONE");
        }
        String pluginItem = PluginItems.normalize(spec);
        if (pluginItem != null) {
            ItemStack resolved = PluginItems.resolve(pluginItem);
            return resolved != null ? resolved : new ItemStack(Material.STONE);
        }
        Material material = ItemUtil.parseMaterial(spec, Material.STONE, warning ->
                org.bukkit.Bukkit.getLogger().warning("[ALInvite] " + warning));
        return material.isAir() ? null : new ItemStack(material);
    }
}
