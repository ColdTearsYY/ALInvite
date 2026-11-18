package com.alinvite.gui.render;

import com.alinvite.gui.MenuItem;
import com.alinvite.gui.Pagination;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class InviteCodeMenuSupportTest {
    private static final Map<String, String> TEXT = Map.of(
        "codes_menu.none", "none", "codes_menu.summary", "available: {count}",
        "codes_menu.preview_row", "code: {code}", "codes_menu.more", "more: {count}");

    private RenderContext context(List<String> codes) {
        var context = new RenderContext(null);
        InviteCodeMenuSupport.populate(context, codes, TEXT::get);
        return context;
    }

    @Test void zeroInventoryStillUpgradesLegacyLoreAndRemovesOldSnapshot() {
        var context = context(List.of("OLD"));
        InviteCodeMenuSupport.populate(context, List.of(), TEXT::get);
        assertTrue(context.lists().containsKey("available_invite_codes"));
        assertEquals(List.of(), context.lists().get("available_invite_codes"));
        assertEquals("0", context.strings().get("invite_available_count"));
        assertEquals("none", context.strings().get("invite_code"));
        assertEquals(List.of("custom header", "available: 0", "none", "custom footer"),
            InviteCodeMenuSupport.upgradeLore(List.of("custom header", "%invite_code%", "custom footer"), context));
    }

    @Test void twoCodesAreBothVisibleAndSourceListIsNotRetained() {
        var codes = new ArrayList<>(List.of("FIRST", "SECOND"));
        var context = context(codes);
        codes.clear();
        assertEquals("2", context.strings().get("invite_available_count"));
        assertEquals(List.of("code: FIRST", "code: SECOND"), context.lists().get("invite_code_preview"));
        assertEquals(List.of("available: 2", "code: FIRST", "code: SECOND"),
            InviteCodeMenuSupport.upgradeLore(List.of("&e%invite_code%", "{invite_code}"), context));
    }

    @Test void overviewIsBoundedButAllCodesRemainInPagedInventory() {
        var codes = IntStream.range(0, 61).mapToObj(i -> "CODE" + i).toList();
        var context = context(codes);
        assertEquals(6, context.lists().get("invite_code_preview").size());
        assertEquals("more: 56", context.lists().get("invite_code_preview").get(5));
        var player = UUID.randomUUID();
        var pages = new HashMap<UUID, Integer>();
        var all = new ArrayList<String>();
        for (int i = 1; i <= 3; i++) {
            pages.put(player, i);
            var page = Pagination.page(context.lists().get("available_invite_codes"), pages, player, 28);
            assertEquals(3, page.totalPages());
            all.addAll(page.entries());
        }
        assertEquals(codes, all);
        pages.put(player, 3);
        assertEquals(1, Pagination.page(List.of("LAST"), pages, player, 28).currentPage());
    }

    @Test void legacyCustomActionsAndLoreRemainUnmodified() {
        var item = new MenuItem();
        var lore = List.of("header", "code: %alinvite_code%", "footer");
        var triggers = new HashMap<String, List<String>>();
        triggers.put("left", List.of("open_veteran"));
        item.setLore(lore);
        item.setTriggers(triggers);
        assertEquals("right", InviteCodeMenuSupport.entryTrigger(item));
        triggers.put("right", List.of("message:custom"));
        assertEquals("shift_right", InviteCodeMenuSupport.entryTrigger(item));
        triggers.put("shift_right", List.of("close"));
        triggers.put("shift_left", List.of("sound:custom"));
        var original = Map.copyOf(triggers);
        assertNull(InviteCodeMenuSupport.entryTrigger(item));
        assertEquals(original, item.getTriggers());
        assertEquals(lore, item.getLore());
        InviteCodeMenuSupport.upgradeLore(lore, context(List.of("A", "B")));
        assertEquals(lore, item.getLore());
    }

    @Test void explicitEntryIsNotDuplicatedAndUnrelatedItemsAreUntouched() {
        var item = new MenuItem();
        item.setLore(List.of("%invite_code%"));
        item.setTriggers(Map.of("left", List.of("AcTiOn:OpEn_CoDeS")));
        assertNull(InviteCodeMenuSupport.entryTrigger(item));
        item.setTriggers(Map.of());
        item.setLore(List.of("unrelated"));
        assertNull(InviteCodeMenuSupport.entryTrigger(item));
        assertSame(item.getLore(), InviteCodeMenuSupport.upgradeLore(item.getLore(), new RenderContext(null)));
    }
}
