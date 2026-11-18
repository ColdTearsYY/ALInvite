package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.GuiPageStore;
import com.alinvite.gui.MenuSessionStore;
import com.alinvite.gui.MenuNames;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;

/**
 * 主菜单：无动态列表区，仅静态按钮（占位符随基础上下文渲染）。
 * 邀请码展示使用与列表一致的只读库存快照。
 */
public class MainMenuRenderer extends BaseMenuRenderer<RenderContext> {

    public MainMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                            ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override
    protected String menuName() {
        return MenuNames.MAIN;
    }

    @Override
    protected RenderContext loadData(Player player) {
        return plugin.getPlaceholderResolver().renderContext(player);
    }

    @Override
    protected RenderContext contextOf(RenderContext data) {
        return data;
    }

    @Override
    protected void fillDynamic(Player player, com.alinvite.gui.MenuConfig config,
                               org.bukkit.inventory.Inventory inventory, RenderContext data, int page) {
        // 主菜单没有动态区
    }
}
