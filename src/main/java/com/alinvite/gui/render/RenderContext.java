package com.alinvite.gui.render;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次菜单打开对应的占位符上下文：玩家级数据异步取齐后快照进本对象，
 * 渲染阶段只做内存替换（零数据库查询）。
 */
public class RenderContext {

    private final Player player;
    private final Map<String, String> strings;
    private final Map<String, List<String>> lists;

    public RenderContext(Player player) {
        this.player = player;
        this.strings = new HashMap<>();
        this.lists = new HashMap<>();
    }

    public RenderContext(Player player, Map<String, String> strings, Map<String, List<String>> lists) {
        this.player = player;
        this.strings = new HashMap<>(strings);
        this.lists = new HashMap<>(lists);
    }

    public RenderContext copy() {
        return new RenderContext(player, strings, lists);
    }

    public RenderContext add(String key, String value) {
        strings.put(key, value == null ? "" : value);
        return this;
    }

    public RenderContext addList(String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            lists.put(key, value);
        }
        return this;
    }

    public Player player() {
        return player;
    }

    public Map<String, String> strings() {
        return strings;
    }

    public Map<String, List<String>> lists() {
        return lists;
    }
}
