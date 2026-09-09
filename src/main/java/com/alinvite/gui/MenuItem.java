package com.alinvite.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单物品/按钮配置 POJO。
 * triggers：left / right / shift_left / shift_right → 动作串列表（支持 delay:，\u001E 分隔多条）。
 * states：动态条目的状态样式（如 locked/available/claimed、available/purchased/current）。
 */
public class MenuItem {

    private String material = "STONE";
    private int customModelData;
    private String name = "";
    private List<String> lore = new ArrayList<>();
    private boolean dynamic;
    private Map<String, StateStyle> states = new LinkedHashMap<>();
    private Map<String, List<String>> triggers = new LinkedHashMap<>();

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public void setCustomModelData(int customModelData) {
        this.customModelData = customModelData;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public void setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
    }

    public Map<String, StateStyle> getStates() {
        return states;
    }

    public void setStates(Map<String, StateStyle> states) {
        this.states = states;
    }

    public StateStyle getState(String state) {
        return state == null ? null : states.get(state);
    }

    public Map<String, List<String>> getTriggers() {
        return triggers;
    }

    public void setTriggers(Map<String, List<String>> triggers) {
        this.triggers = triggers;
    }

    public List<String> getTriggerActions(String trigger) {
        if (trigger == null) {
            return List.of();
        }
        List<String> actions = triggers.get(trigger);
        return actions == null ? List.of() : actions;
    }

    public static class StateStyle {
        private String material;
        private int customModelData = Integer.MIN_VALUE; // -1 哨兵：未配置则继承主样式
        private String name;
        private List<String> lore;

        public String getMaterial() {
            return material;
        }

        public void setMaterial(String material) {
            this.material = material;
        }

        public boolean hasMaterial() {
            return material != null && !material.isBlank();
        }

        public int getCustomModelData() {
            return customModelData;
        }

        public void setCustomModelData(int customModelData) {
            this.customModelData = customModelData;
        }

        public boolean hasCustomModelData() {
            return customModelData != Integer.MIN_VALUE;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean hasName() {
            return name != null && !name.isBlank();
        }

        public List<String> getLore() {
            return lore;
        }

        public void setLore(List<String> lore) {
            this.lore = lore;
        }

        public boolean hasLore() {
            return lore != null && !lore.isEmpty();
        }
    }
}
