package com.alinvite.gui;

import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家 GUI 会话存储：uuid → (menuName, inventory)。
 * 异步数据回填前必须用 {@link #isTracked} 做引用相等校验，
 * 防止旧数据覆盖新菜单或幽灵重开已关闭的界面。
 */
public class MenuSessionStore {

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public void track(UUID playerId, String menuName, Inventory inventory) {
        sessions.put(playerId, new Session(menuName, inventory));
    }

    public Session get(UUID playerId) {
        return sessions.get(playerId);
    }

    public String currentMenu(UUID playerId, Inventory openInventory) {
        Session session = sessions.get(playerId);
        return session != null && session.inventory() == openInventory ? session.menuName() : null;
    }

    public boolean isTracked(UUID playerId, Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        Session session = sessions.get(playerId);
        return session != null && session.inventory() == inventory;
    }

    public Inventory inventory(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? null : session.inventory();
    }

    /**
     * 仅当当前跟踪的就是该界面时才清除（翻页/切换菜单时旧的 Close 事件不会误清新会话）。
     */
    public void clear(UUID playerId) {
        sessions.remove(playerId);
    }

    public void clearAll(UUID playerId) {
        sessions.remove(playerId);
    }

    public boolean clear(UUID playerId, Inventory inventory) {
        Session session = sessions.get(playerId);
        if (session != null && session.inventory() == inventory) {
            return sessions.remove(playerId, session);
        }
        return false;
    }

    public void clearAll() {
        sessions.clear();
    }

    public record Session(String menuName, Inventory inventory) {
    }
}
