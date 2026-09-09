package com.alinvite.utils;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件物品（自定义材质）解析：ce/craftengine、ia/itemsadder、oraxen 前缀。
 * 反射调用对应插件 API，解析结果按引用缓存（菜单重复渲染零开销），
 * 解析失败每个引用只警告一次并降级为配置的回退材质。
 * 参考 ALwarp ItemUtil 的做法，仅保留菜单图标所需的构建路径。
 */
public final class PluginItems {

    private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private PluginItems() {
    }

    /**
     * 规范化物品引用：识别 ce/craftengine/ia/itemsadder/oraxen 前缀。
     *
     * @return 规范化后的 "前缀:id"，不是插件物品返回 null
     */
    public static String normalize(String spec) {
        if (spec == null) {
            return null;
        }
        String normalized = spec.trim().replace('：', ':');
        if (normalized.length() < 3) {
            return null;
        }
        int colon = normalized.indexOf(':');
        if (colon <= 0 || colon >= normalized.length() - 1) {
            return null;
        }
        String prefix = normalized.substring(0, colon).toLowerCase(Locale.ROOT);
        String id = normalized.substring(colon + 1).trim();
        if (id.isEmpty()) {
            return null;
        }
        return switch (prefix) {
            case "ce", "craftengine" -> "ce:" + id;
            case "ia", "itemsadder" -> "ia:" + id;
            case "oraxen" -> "oraxen:" + id;
            default -> null;
        };
    }

    /** 解析插件物品（带缓存，返回克隆）。解析失败返回 null。 */
    public static ItemStack resolve(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        ItemStack cached = CACHE.get(normalized);
        if (cached != null) {
            return cached.clone();
        }
        int colon = normalized.indexOf(':');
        String prefix = normalized.substring(0, colon);
        String id = normalized.substring(colon + 1);
        ItemStack resolved = switch (prefix) {
            case "ce" -> resolveCraftEngine(id);
            case "ia" -> resolveItemsAdder(id);
            case "oraxen" -> resolveOraxen(id);
            default -> null;
        };
        if (resolved == null) {
            if (WARNED.add(normalized)) {
                Bukkit.getLogger().warning("[ALInvite] 自定义物品解析失败: " + normalized
                    + "（请确认对应插件已启用且物品 ID 存在）");
            }
            return null;
        }
        CACHE.put(normalized, resolved.clone());
        return resolved.clone();
    }

    public static void clearCache() {
        CACHE.clear();
    }

    // ── CraftEngine：CraftEngineItems.byId(id) → 定义对象 → 构建为 Bukkit ItemStack ──

    private static ItemStack resolveCraftEngine(String id) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
                return null;
            }
            Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            Object definition = null;
            try {
                definition = api.getMethod("byId", String.class).invoke(null, id);
            } catch (NoSuchMethodException ignored) {
            }
            if (definition == null) {
                for (String name : List.of("byID", "getById", "getByID", "getCustomItem", "getItem")) {
                    Method method = findMethod(api, name, 1);
                    if (method == null) {
                        continue;
                    }
                    definition = method.invoke(null, id);
                    if (definition != null) {
                        break;
                    }
                }
            }
            if (definition == null) {
                return null;
            }
            return extractBukkitItemStack(definition);
        } catch (Exception e) {
            Bukkit.getLogger().fine("[ALInvite] CE 物品构建失败 " + id + ": " + e.getMessage());
            return null;
        }
    }

    private static ItemStack extractBukkitItemStack(Object definition) {
        for (String name : List.of("buildBukkitItem", "buildItemStack", "build", "create", "getItemStack", "getBukkitItemStack")) {
            Method method = findMethod(definition.getClass(), name, 0);
            if (method == null) {
                continue;
            }
            try {
                Object result = method.invoke(definition);
                if (result instanceof ItemStack stack && !stack.getType().isAir()) {
                    return stack;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ── ItemsAdder：CustomStack.getInstance(id).getItemStack() ──

    private static ItemStack resolveItemsAdder(String id) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
                return null;
            }
            Class<?> api = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = api.getMethod("getInstance", String.class).invoke(null, id);
            if (stack == null) {
                return null;
            }
            ItemStack item = (ItemStack) stack.getClass().getMethod("getItemStack").invoke(stack);
            return item == null ? null : item.clone();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Oraxen：OraxenItems.getItemById(id).build() ──

    private static ItemStack resolveOraxen(String id) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("Oraxen")) {
                return null;
            }
            Class<?> api = Class.forName("io.th0rgal.oraxen.items.OraxenItems");
            Object builder = api.getMethod("getItemById", String.class).invoke(null, id);
            if (builder == null) {
                return null;
            }
            ItemStack item = (ItemStack) builder.getClass().getMethod("build").invoke(builder);
            return item == null ? null : item.clone();
        } catch (Exception e) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }
}
