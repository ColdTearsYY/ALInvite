package com.alinvite.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

/**
 * 动作串在物品 PDC 中的编解码。渲染时写入，点击时零配置查询。
 * 动态条目的 ID（如里程碑数值、礼包 ID）直接编码进动作串的取值部分。
 */
public final class ActionPdc {

    public static final String SEPARATOR = MenuActionParser.ACTION_SEPARATOR;

    private final NamespacedKey left;
    private final NamespacedKey right;
    private final NamespacedKey shiftLeft;
    private final NamespacedKey shiftRight;

    public ActionPdc(Plugin plugin) {
        this.left = new NamespacedKey(plugin, "gui_action");
        this.right = new NamespacedKey(plugin, "gui_right_action");
        this.shiftLeft = new NamespacedKey(plugin, "gui_shift_left_action");
        this.shiftRight = new NamespacedKey(plugin, "gui_shift_right_action");
    }

    public void write(ItemMeta meta, String trigger, List<String> actions) {
        if (meta == null || actions == null || actions.isEmpty()) {
            return;
        }
        NamespacedKey key = keyOf(trigger);
        if (key == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, String.join(SEPARATOR, actions));
    }

    public void clear(ItemMeta meta) {
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.remove(left);
        container.remove(right);
        container.remove(shiftLeft);
        container.remove(shiftRight);
    }

    /** clickType: left / right / shift_left / shift_right */
    public String read(ItemMeta meta, String clickType) {
        NamespacedKey key = keyOf(clickType);
        if (key == null || meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private NamespacedKey keyOf(String trigger) {
        if (trigger == null) {
            return null;
        }
        return switch (trigger.toLowerCase(Locale.ROOT)) {
            case "left" -> left;
            case "right" -> right;
            case "shift_left" -> shiftLeft;
            case "shift_right" -> shiftRight;
            default -> null;
        };
    }
}
