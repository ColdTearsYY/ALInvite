package com.alinvite.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作串解析：多个动作以 \u001E 分隔按序执行，支持 delay:/wait: 累积延迟。
 * 纯逻辑类，无 Bukkit 依赖，可单测。
 */
public final class MenuActionParser {

    public static final String ACTION_SEPARATOR = "\u001E";

    private MenuActionParser() {
    }

    public static List<PlannedAction> parse(String encodedActions) {
        List<PlannedAction> planned = new ArrayList<>();
        if (encodedActions == null || encodedActions.isBlank()) {
            return List.of();
        }
        long delayTicks = 0L;
        for (String part : encodedActions.split(ACTION_SEPARATOR, -1)) {
            String action = part.trim();
            if (action.isEmpty()) {
                continue;
            }
            String delayValue = delayValue(action);
            if (delayValue != null) {
                delayTicks = safeAdd(delayTicks, parseDelayTicks(delayValue));
            } else {
                planned.add(new PlannedAction(action, delayTicks));
            }
        }
        return List.copyOf(planned);
    }

    public static boolean containsSound(String encodedActions) {
        if (encodedActions == null) {
            return false;
        }
        for (String part : encodedActions.split(ACTION_SEPARATOR, -1)) {
            if (part.trim().toLowerCase(java.util.Locale.ROOT).startsWith("sound:")) {
                return true;
            }
        }
        return false;
    }

    /** 支持 "10t"（tick）、"500ms"、"1.5s"、纯数字（tick）。 */
    public static long parseDelayTicks(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return 0L;
        }
        try {
            if (normalized.endsWith("ms")) {
                return positiveCeil(Double.parseDouble(normalized.substring(0, normalized.length() - 2)) / 50.0D);
            }
            if (normalized.endsWith("t")) {
                return positiveCeil(Double.parseDouble(normalized.substring(0, normalized.length() - 1)));
            }
            if (normalized.endsWith("s")) {
                return positiveCeil(Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 20.0D);
            }
            return Math.max(0L, Long.parseLong(normalized));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static String delayValue(String action) {
        int separator = action.indexOf(':');
        if (separator < 0) {
            return null;
        }
        String type = action.substring(0, separator).trim().toLowerCase(java.util.Locale.ROOT);
        if (type.equals("delay") || type.equals("wait")) {
            return action.substring(separator + 1).trim();
        }
        return null;
    }

    private static long positiveCeil(double value) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            return 0L;
        }
        if (value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.ceil(value);
    }

    private static long safeAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    public record PlannedAction(String action, long delayTicks) {
    }
}
