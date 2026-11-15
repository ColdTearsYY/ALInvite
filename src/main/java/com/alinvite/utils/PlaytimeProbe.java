package com.alinvite.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * Reads a player's cumulative online hours.
 *
 * <p>All Bukkit/PAPI access happens on the caller's thread; callers must invoke this from the
 * player's entity thread on Folia. Returns -1 when no source could be resolved.
 */
public final class PlaytimeProbe {

    private PlaytimeProbe() {
    }

    public static double readHours(Player player, String placeholder, String valueUnit,
                                   boolean useBuiltin) {
        if (player == null) {
            return -1;
        }
        try {
            if (placeholder != null && !placeholder.isBlank()
                && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                String result = PlaceholderAPI.setPlaceholders(player, placeholder);
                if (result != null && !result.equals(placeholder)) {
                    double raw = parseValue(result);
                    if (raw >= 0) {
                        return convertToHours(raw, valueUnit);
                    }
                }
            }
            if (useBuiltin) {
                return player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 72000.0;
            }
        } catch (Throwable ignored) {
            // Fall through to the unresolved sentinel.
        }
        return -1;
    }

    /** Parses a plain number or the first number inside a formatted placeholder result. */
    public static double parseValue(String result) {
        if (result == null || result.isBlank()) {
            return -1;
        }
        String cleaned = result.replaceAll("§[0-9a-fk-or]", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
        }
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(cleaned);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    public static double convertToHours(double rawValue, String valueUnit) {
        String unit = valueUnit == null ? "hours" : valueUnit.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (unit) {
            case "minutes" -> rawValue / 60.0;
            case "seconds" -> rawValue / 3600.0;
            case "ticks" -> rawValue / 72000.0;
            default -> rawValue;
        };
    }
}
