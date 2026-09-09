package com.alinvite.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家分页状态：uuid → (menuName → page)。页码跨重开保留（与旧行为一致）。
 */
public class GuiPageStore {

    private final Map<UUID, Map<String, Integer>> pages = new ConcurrentHashMap<>();

    public int get(UUID playerId, String menuName) {
        Map<String, Integer> menuPages = pages.get(playerId);
        Integer page = menuPages == null ? null : menuPages.get(menuName);
        return page == null ? 1 : page;
    }

    public void set(UUID playerId, String menuName, int page) {
        pages.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(menuName, Math.max(0, page));
    }

    public void clear(UUID playerId) {
        pages.remove(playerId);
    }

    public void clearAll() {
        pages.clear();
    }
}
