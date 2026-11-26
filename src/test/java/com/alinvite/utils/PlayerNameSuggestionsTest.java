package com.alinvite.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerNameSuggestionsTest {
    @Test
    void filtersCaseInsensitivelyAndDeduplicatesNamesAcrossBackendLists() {
        assertEquals(List.of("Havasmoj", "Havanna"), PlayerNameSuggestions.complete(
            List.of("Havasmoj", "havasmoj", "HarborFox", "Havanna", ""), "HAV"));
    }

    @Test
    void emptyPrefixReturnsAllValidNamesAndNullInputIsSafe() {
        assertEquals(List.of("OfflineUser"), PlayerNameSuggestions.complete(java.util.Arrays.asList("OfflineUser", null), ""));
        assertEquals(List.of(), PlayerNameSuggestions.complete(null, "off"));
    }
    @Test
    void capsLargeSuggestionSets() {
        List<String> names = java.util.stream.IntStream.range(0, 250)
            .mapToObj(index -> "Player" + index).toList();
        assertEquals(100, PlayerNameSuggestions.complete(names, "Player").size());
    }
}
