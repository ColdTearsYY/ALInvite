package com.alinvite.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按钮点击冷却（防抖）。key = uuid:menu:slot。
 */
public class ButtonCooldowns {

    private final Map<String, Long> lastClickMillis = new ConcurrentHashMap<>();

    public boolean isCoolingDown(UUID playerId, String menuName, int slot, long now, long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return false;
        }
        Long last = lastClickMillis.get(key(playerId, menuName, slot));
        return last != null && now - last < cooldownMillis;
    }

    public void mark(UUID playerId, String menuName, int slot, long now) {
        lastClickMillis.put(key(playerId, menuName, slot), now);
    }

    public void clear(UUID playerId) {
        String prefix = playerId.toString() + "\u0000";
        lastClickMillis.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void clearAll() {
        lastClickMillis.clear();
    }

    private static String key(UUID playerId, String menuName, int slot) {
        return playerId + "\u0000" + menuName + "\u0000" + slot;
    }
}
