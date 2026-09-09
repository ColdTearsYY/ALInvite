package com.alinvite.gui;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaginationTest {

    @Test
    void splitsEntriesAndClampsPage() {
        List<Integer> entries = List.of(1, 2, 3, 4, 5);
        Map<UUID, Integer> pages = new HashMap<>();
        UUID player = UUID.randomUUID();
        pages.put(player, 99); // 越界页码应被夹取

        Pagination.Page<Integer> page = Pagination.page(entries, pages, player, 2);
        assertEquals(3, page.totalPages());
        assertEquals(3, page.currentPage());
        assertEquals(List.of(5), page.entries());
        assertEquals(3, pages.get(player));
    }

    @Test
    void emptyEntriesYieldsSinglePage() {
        Map<UUID, Integer> pages = new HashMap<>();
        Pagination.Page<Integer> page = Pagination.page(List.of(), pages, UUID.randomUUID(), 5);
        assertEquals(1, page.totalPages());
        assertEquals(0, page.entries().size());
    }

    @Test
    void exactFitPages() {
        List<Integer> entries = List.of(1, 2, 3, 4);
        Map<UUID, Integer> pages = new HashMap<>();
        UUID player = UUID.randomUUID();
        pages.put(player, 1);

        Pagination.Page<Integer> first = Pagination.page(entries, pages, player, 2);
        assertEquals(List.of(1, 2), first.entries());

        pages.put(player, 2);
        Pagination.Page<Integer> second = Pagination.page(entries, pages, player, 2);
        assertEquals(List.of(3, 4), second.entries());
        assertEquals(2, second.totalPages());
    }

    @Test
    void pageSizeMustBePositive() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Pagination.page(List.of("a"), new HashMap<>(), UUID.randomUUID(), 0));
    }
}
