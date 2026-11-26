package com.alinvite.utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Case-insensitive player-name filtering shared by command tab completion and unit tests. */
public final class PlayerNameSuggestions {
    private PlayerNameSuggestions() { }

    public static List<String> complete(List<String> names, String prefix) {
        String query = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, String> matches = new LinkedHashMap<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank() && name.toLowerCase(Locale.ROOT).startsWith(query)) {
                    matches.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        return matches.values().stream().limit(100).toList();
    }
}
