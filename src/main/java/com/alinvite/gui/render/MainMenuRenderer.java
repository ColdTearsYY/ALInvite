package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.GuiPageStore;
import com.alinvite.gui.MenuSessionStore;
import com.alinvite.gui.MenuNames;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * 主菜单：无动态列表区，仅静态按钮（占位符随基础上下文渲染）。
 * 保留旧行为：有老玩家权限且尚无邀请码的玩家打开菜单时自动生成邀请码。
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
    public void open(Player player) {
        String veteranPermission = plugin.getConfigManager().getConfig()
            .getString("invite_code.veteran_permission", "alinvite.veteran");
        boolean veteran = player.hasPermission(veteranPermission);

        CompletableFuture<Void> ready = veteran
            ? plugin.getInviteManager().ensureInviteCode(player.getUniqueId()).thenAccept(code -> { })
            : CompletableFuture.completedFuture(null);

        ready.thenRun(() -> super.open(player))
            .exceptionally(throwable -> {
                plugin.getLogger().warning("准备主菜单数据失败: " + throwable.getMessage());
                super.open(player);
                return null;
            });
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
