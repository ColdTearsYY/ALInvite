package com.alinvite.gui.render;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 菜单文本渲染管线：
 *  1. 用上下文替换内部占位符（%key% 与旧版 {key} 两种写法）；
 *  2. 第三方 PAPI 占位符交给 PlaceholderAPI；
 *  3. 条件行：内部占位符未命中 → 整行删除；旧版 {reward_lore_N} 未命中 → 整行删除。
 * 纯字符串逻辑（PAPI 调用除外），渲染阶段零数据库查询。
 */
public final class MenuText {

    /** 插件内部占位符键集合：出现在配置里但上下文未提供时，所在行整行删除。 */
    public static final Set<String> INTERNAL_KEYS = Set.of(
            "page", "total_pages", "prev_page_hint", "next_page_hint",
            "bind_status", "inviter_name", "invite_code", "total_invites", "total_rebate", "contribution",
            "has_gift", "gift_name", "gift_status", "gift_remaining_days", "gift_id", "gift_material",
            "gift_reward_lore", "next_milestone",
            "milestone_name", "milestone_required", "milestone_status",
            "current", "required", "remaining",
            "price_money", "price_points", "duration_text", "duration_days",
            "unclaimed_rebate", "rebate_rate", "view_name", "toggle_target", "target_name",
            "record_time", "record_text", "record_source", "record_value",
            "reward_lore");

    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z0-9_]+)%");
    private static final Pattern INDEXED_REWARD = Pattern.compile("\\{reward_lore_(\\d+)\\}");

    private MenuText() {
    }

    /** 渲染单行；返回 null 表示该行为条件行且条件不成立，应整行删除。 */
    public static String render(String line, RenderContext context) {
        if (line == null) {
            return null;
        }
        String result = line;
        Map<String, String> strings = context.strings();
        for (Map.Entry<String, String> entry : strings.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue())
                    .replace("{" + entry.getKey() + "}", entry.getValue());
        }

        if (result.contains("{reward_lore_")) {
            Matcher matcher = INDEXED_REWARD.matcher(result);
            StringBuilder rebuilt = new StringBuilder();
            int copied = 0;
            boolean dropped = false;
            while (matcher.find()) {
                List<String> rewardLines = context.lists().get("reward_lore");
                int index = Integer.parseInt(matcher.group(1));
                if (rewardLines == null || index >= rewardLines.size()) {
                    dropped = true;
                    break;
                }
                rebuilt.append(result, copied, matcher.start()).append(rewardLines.get(index));
                copied = matcher.end();
            }
            if (dropped) {
                return null;
            }
            rebuilt.append(result.substring(copied));
            result = rebuilt.toString();
        }

        result = applyPapi(result, context.player());

        Matcher unresolved = PLACEHOLDER.matcher(result);
        while (unresolved.find()) {
            if (INTERNAL_KEYS.contains(unresolved.group(1).toLowerCase(Locale.ROOT))) {
                return null;
            }
        }
        for (String key : INTERNAL_KEYS) {
            if (result.contains("{" + key + "}")) {
                return null;
            }
        }
        return result;
    }

    /** 渲染整段 lore：整行单占位符可展开为多行（如 %reward_lore%）。 */
    public static List<String> renderLore(List<String> lore, RenderContext context) {
        List<String> out = new ArrayList<>();
        if (lore == null) {
            return out;
        }
        for (String line : lore) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            String bareKey = null;
            if (trimmed.matches("^%[a-z0-9_]+%$") || trimmed.matches("^\\{[a-z0-9_]+\\}$")) {
                bareKey = trimmed.substring(1, trimmed.length() - 1).toLowerCase(Locale.ROOT);
            }
            if (bareKey != null) {
                List<String> expansion = context.lists().get(bareKey);
                if (expansion != null) {
                    for (String expanded : expansion) {
                        String rendered = render(expanded, context);
                        if (rendered != null) {
                            out.add(rendered);
                        }
                    }
                    continue;
                }
                if (INTERNAL_KEYS.contains(bareKey)) {
                    continue;
                }
            }
            String rendered = render(line, context);
            if (rendered != null) {
                out.add(rendered);
            }
        }
        return out;
    }

    /**
     * 解析动作串中的上下文占位符（供 PDC 写入前调用）。
     * 与 render 不同：不删行、不走 PAPI（动作在执行时才需要 PAPI 的极少）。
     * 未命中的内部占位符保留原文（由执行端兜底处理）。
     */
    public static String renderActionStr(String action, RenderContext context) {
        if (action == null) {
            return null;
        }
        String result = action;
        for (Map.Entry<String, String> entry : context.strings().entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue())
                    .replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /** 解析动作列表中的占位符。 */
    public static java.util.List<String> renderActionList(java.util.List<String> actions, RenderContext context) {
        if (actions == null) {
            return List.of();
        }
        java.util.List<String> out = new ArrayList<>();
        for (String a : actions) {
            String r = renderActionStr(a, context);
            out.add(r != null ? r : a);
        }
        return out;
    }

    public static String applyPapi(String text, org.bukkit.entity.Player player) {
        if (text == null || text.isEmpty() || player == null) {
            return text;
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        return text;
    }
}
