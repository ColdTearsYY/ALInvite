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
            item.getTriggers().forEach((trigger, actions) ->
                pdc.write(meta, trigger, MenuText.renderActionList(actions, context)));
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
