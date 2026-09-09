package com.alinvite.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单配置 POJO（加载后不可变使用）。
 * shape 每行 9 字符，字符对应 items 里的键；'#' 约定为背景，' ' 空置。
 */
public class MenuConfig {

    private final String name;
    private String title;
    private List<String> shape = new ArrayList<>();
    private Map<String, MenuItem> items = new LinkedHashMap<>();

    /** 预计算：字符 → 槽位序。 */
    private final Map<Character, List<Integer>> charSlots = new HashMap<>();
    /** 预计算：动态字符槽位序（= 每页容量）。 */
    private List<Integer> dynamicSlots = List.of();

    public MenuConfig(String name) {
        this.name = name;
    }

    /** 加载完成后调用一次，预计算槽位映射。 */
    public void freeze() {
        charSlots.clear();
        List<Integer> dynamic = new ArrayList<>();
        int slot = 0;
        for (String row : shape) {
            for (char c : row.toCharArray()) {
                charSlots.computeIfAbsent(c, ignored -> new ArrayList<>()).add(slot);
                MenuItem item = items.get(String.valueOf(c));
                if (item != null && item.isDynamic()) {
                    dynamic.add(slot);
                }
                slot++;
            }
        }
        dynamicSlots = List.copyOf(dynamic);
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title == null || title.isBlank() ? "菜单" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getShape() {
        return shape;
    }

    public void setShape(List<String> shape) {
        this.shape = shape;
    }

    public Map<String, MenuItem> getItems() {
        return items;
    }

    public void setItems(Map<String, MenuItem> items) {
        this.items = items;
    }

    public int size() {
        return Math.max(9, shape.size() * 9);
    }

    public List<Integer> slotsOf(char c) {
        return charSlots.getOrDefault(c, List.of());
    }

    public List<Integer> getDynamicSlots() {
        return dynamicSlots;
    }



}
