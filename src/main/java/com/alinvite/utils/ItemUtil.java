package com.alinvite.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 物品构建工具：材质解析容错、背景物品。
 */
public final class ItemUtil {

    private ItemUtil() {
    }

    /** 材质解析容错：非法值降级 fallback 并警告一次，不拖垮整张菜单。 */
    public static Material parseMaterial(String name, Material fallback, Consumer<String> warn) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return fallback;
        }
        try {
            return Material.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            if (warn != null) {
                warn.accept("未知材质: " + name + "，已降级为 " + fallback.name());
            }
            return fallback;
        }
    }

    public static ItemStack createItem(Material material, int amount) {
        return new ItemStack(material, Math.max(1, amount));
    }
}
