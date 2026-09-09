package com.alinvite.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuActionParserTest {

    @Test
    void parsesSingleAction() {
        List<MenuActionParser.PlannedAction> result = MenuActionParser.parse("open_shop");
        assertEquals(1, result.size());
        assertEquals("open_shop", result.get(0).action());
        assertEquals(0L, result.get(0).delayTicks());
    }

    @Test
    void parsesSequentialActionsWithDelay() {
        String encoded = String.join(MenuActionParser.ACTION_SEPARATOR,
                "sound: UI_BUTTON_CLICK-1-1", "delay:10t", "close");
        List<MenuActionParser.PlannedAction> result = MenuActionParser.parse(encoded);
        assertEquals(2, result.size());
        assertEquals("sound: UI_BUTTON_CLICK-1-1", result.get(0).action());
        assertEquals(0L, result.get(0).delayTicks());
        assertEquals("close", result.get(1).action());
        assertEquals(10L, result.get(1).delayTicks());
    }

    @Test
    void accumulatesDelays() {
        String encoded = String.join(MenuActionParser.ACTION_SEPARATOR,
                "delay:1s", "delay:500ms", "message: hi");
        List<MenuActionParser.PlannedAction> result = MenuActionParser.parse(encoded);
        assertEquals(1, result.size());
        // 1s = 20t, 500ms = 10t
        assertEquals(30L, result.get(0).delayTicks());
    }

    @Test
    void delayUnitsConvert() {
        assertEquals(20L, MenuActionParser.parseDelayTicks("1s"));
        assertEquals(10L, MenuActionParser.parseDelayTicks("500ms"));
        assertEquals(7L, MenuActionParser.parseDelayTicks("7t"));
        assertEquals(5L, MenuActionParser.parseDelayTicks("5"));
        assertEquals(0L, MenuActionParser.parseDelayTicks("abc"));
    }

    @Test
    void blankAndNullInput() {
        assertTrue(MenuActionParser.parse(null).isEmpty());
        assertTrue(MenuActionParser.parse("  ").isEmpty());
        assertFalse(MenuActionParser.containsSound(null));
        assertTrue(MenuActionParser.containsSound("sound: X"));
        assertFalse(MenuActionParser.containsSound("close"));
    }
}
