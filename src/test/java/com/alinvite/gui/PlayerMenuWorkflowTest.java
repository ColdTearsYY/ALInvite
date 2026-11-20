package com.alinvite.gui;

import com.alinvite.database.ledger.AtomicInviteLedger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMenuWorkflowTest {
    private YamlConfiguration config(String locale, String menu) {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/" + locale + "/" + menu + ".yml"));
    }
    @Test void commonActionsHaveVisibleLeftClickEntriesInBothLocales() {
        for (String locale : List.of("menus", "menus_en")) {
            var config = config(locale, MenuNames.MAIN);
            var items = config.getConfigurationSection("main_menu.items");
            Set<String> visible = new HashSet<>();
            String shape = String.join("", config.getStringList("main_menu.shape"));
            for (String key : items.getKeys(false)) {
                if (shape.contains(key)) visible.addAll(items.getStringList(key + ".triggers.left"));
            }
            assertTrue(visible.containsAll(List.of("refresh_main", "open_codes", "open_pending", "open_deliveries",
                "open_veteran", "open_shop", "open_rebate_history", "claim_rebate", "input_code", "close")), locale);
            assertFalse(config.saveToString().contains("/alinvite"));
            var pending = config(locale, MenuNames.PENDING);
            assertEquals(List.of("input_invitee"), pending.getStringList("pending_invites.items.A.triggers.left"));
            assertEquals(List.of("cancel_pending:%pending_action%"), pending.getStringList("pending_invites.items.M.triggers.left"));
        }
    }
    @Test void readOnlyRewardEntriesHaveNoMutationActions() {
        for (String locale : List.of("menus", "menus_en")) {
            var config = config(locale, MenuNames.DELIVERIES);
            assertFalse(config.contains(MenuNames.DELIVERIES + ".items.M.triggers"));
            assertTrue(config.getStringList(MenuNames.DELIVERIES + ".items.S.lore").size() > 0);
        }
    }
    @Test void newLanguageKeysStayInParity() {
        var zh = YamlConfiguration.loadConfiguration(new File("src/main/resources/languages/zh_cn.yml"));
        var en = YamlConfiguration.loadConfiguration(new File("src/main/resources/languages/en_us.yml"));
        for (String root : List.of("pending_menu", "player_menu")) {
            assertEquals(zh.getConfigurationSection(root).getKeys(true), en.getConfigurationSection(root).getKeys(true));
            for (String key : zh.getConfigurationSection(root).getKeys(true)) {
                if (zh.isString(root + "." + key)) {
                    assertNotNull(en.getString(root + "." + key));
                    assertFalse(en.getString(root + "." + key).isBlank());
                }
            }
        }
    }
    @Test void cancellationRejectsForeignAndReplacedReservationsIncludingLegacyNullIds() {
        UUID owner = UUID.randomUUID();
        var row = new AtomicInviteLedger.Pending("friend", "Friend", owner, "private-ip", "SECRET", "old-id", 0, null);
        String key = PendingCancellation.key(row);
        assertTrue(PendingCancellation.matches(row, owner, key));
        assertFalse(PendingCancellation.matches(row, UUID.randomUUID(), key));
        assertFalse(PendingCancellation.matches(null, owner, key));
        var replacement = new AtomicInviteLedger.Pending("friend", "Friend", owner, null, "SECRET", "new-id", 0, null);
        assertFalse(PendingCancellation.matches(replacement, owner, key));
        var legacy = new AtomicInviteLedger.Pending("friend", "Friend", owner, null, "SECRET", null, 0, null);
        assertTrue(PendingCancellation.matches(legacy, owner, PendingCancellation.key(legacy)));
        assertFalse(key.contains("SECRET"));
    }
    @Test void lastNavigationWinsAndOldInventoryCloseCannotInvalidateReplacement() {
        var sessions = new MenuSessionStore();
        UUID player = UUID.randomUUID();
        Object first = sessions.beginLoad(player), second = sessions.beginLoad(player);
        assertFalse(sessions.isLatestLoad(player, first));
        assertTrue(sessions.isLatestLoad(player, second));
        var oldInventory = inventory(); var replacement = inventory();
        sessions.track(player, MenuNames.MAIN, oldInventory);
        sessions.track(player, MenuNames.PENDING, replacement);
        assertFalse(sessions.clear(player, oldInventory));
        assertTrue(sessions.isLatestLoad(player, second));
        assertTrue(sessions.clear(player, replacement));
        assertFalse(sessions.isLatestLoad(player, second));
        Object third = sessions.beginLoad(player);
        sessions.clearAll(player);
        assertFalse(sessions.isLatestLoad(player, third));
    }
    @Test void scalarStatusLinesRenderWhileMissingLinesStayHidden() {
        var context = new com.alinvite.gui.render.RenderContext(null)
            .add("quota_notice", "Capacity reached")
            .add("bind_hint", "Already bound")
            .add("contribution_info", "For later redemption");
        context.lists().put("reward_lore", List.of("Reward one", "Reward two"));
        assertEquals(List.of("Capacity reached", "Already bound", "For later redemption", "Reward one", "Reward two"),
            com.alinvite.gui.render.MenuText.renderLore(List.of("%quota_notice%", "{bind_hint}",
                "%contribution_info%", "%quota_progress%", "%reward_lore%"), context));
    }
    private org.bukkit.inventory.Inventory inventory() {
        return (org.bukkit.inventory.Inventory) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{org.bukkit.inventory.Inventory.class}, (proxy, method, args) -> null);
    }
}
