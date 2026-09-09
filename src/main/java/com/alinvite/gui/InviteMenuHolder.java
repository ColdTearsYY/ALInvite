package com.alinvite.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Shape 菜单的 InventoryHolder，即菜单的身份标识。
 * Paper 1.21+ 的拖拽校验要求 getInventory() 返回真实引用，
 * 否则拖拽保护失效、物品可被取走——建箱后必须调用 {@link #setInventory} 回填。
 */
public class InviteMenuHolder implements InventoryHolder {

    private final String menuName;
    private Inventory inventory;

    public InviteMenuHolder(String menuName) {
        this.menuName = menuName;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public String getMenuName() {
        return menuName;
    }
}
