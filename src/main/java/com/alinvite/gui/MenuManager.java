package com.alinvite.gui;

import com.alinvite.ALInvite;
import com.alinvite.utils.PlaceholderResolver;
import org.bukkit.entity.Player;

/**
 * GUI 门面：菜单配置加载、会话/页码/冷却存储、点击管线、输入服务、三个渲染器。
 */
public class MenuManager {

    private final ALInvite plugin;
    private final MenuConfigLoader loader;
    private final MenuSessionStore sessions = new MenuSessionStore();
    private final GuiPageStore pages = new GuiPageStore();
    private final ButtonCooldowns cooldowns = new ButtonCooldowns();
    private final ActionPdc pdc;
    private final MenuActionRegistry registry;
    private final MenuClickListener clickListener;
    private final MenuInputService inputService;
    private final PlaceholderResolver placeholderResolver;

    private final com.alinvite.gui.render.MainMenuRenderer mainRenderer;
    private final com.alinvite.gui.render.VeteranMenuRenderer veteranRenderer;
    private final com.alinvite.gui.render.ShopMenuRenderer shopRenderer;
    private final com.alinvite.gui.render.RebateHistoryRenderer rebateHistoryRenderer;

    public MenuManager(ALInvite plugin) {
        this.plugin = plugin;
        this.loader = new MenuConfigLoader(plugin);
        this.loader.loadAll();
        this.pdc = new ActionPdc(plugin);
        this.registry = new MenuActionRegistry(plugin);
        this.clickListener = new MenuClickListener(plugin, this);
        this.inputService = new MenuInputService(plugin);
        this.placeholderResolver = new PlaceholderResolver(plugin);

        this.mainRenderer = new com.alinvite.gui.render.MainMenuRenderer(plugin, sessions, pages, plugin.getScheduler(), pdc);
        this.veteranRenderer = new com.alinvite.gui.render.VeteranMenuRenderer(plugin, sessions, pages, plugin.getScheduler(), pdc);
        this.shopRenderer = new com.alinvite.gui.render.ShopMenuRenderer(plugin, sessions, pages, plugin.getScheduler(), pdc);
        this.rebateHistoryRenderer = new com.alinvite.gui.render.RebateHistoryRenderer(plugin, sessions, pages, plugin.getScheduler(), pdc);
    }

    public void openRebateHistoryMenu(Player player) {
        rebateHistoryRenderer.open(player);
    }

    /** 按菜单名打开（导航 back 使用）。 */
    public void openByName(Player player, String menuName) {
        if (menuName == null) {
            return;
        }
        switch (menuName) {
            case MenuNames.MAIN -> openMainMenu(player);
            case MenuNames.VETERAN -> openVeteranMenu(player);
            case MenuNames.SHOP -> openShopMenu(player);
            case MenuNames.REBATE_HISTORY -> openRebateHistoryMenu(player);
            default -> { }
        }
    }

    /**
     * 菜单固定层级（"返回"按钮按进入顺序回上级）：
     * 主菜单 → 邀请中心 → 礼包商店；主菜单 → 返利记录。
     */
    public String getParentMenu(String menuName) {
        if (menuName == null) {
            return null;
        }
        return switch (menuName) {
            case MenuNames.VETERAN -> MenuNames.MAIN;
            case MenuNames.REBATE_HISTORY -> MenuNames.MAIN;
            case MenuNames.SHOP -> MenuNames.VETERAN;
            default -> null;
        };
    }


    public void openMainMenu(Player player) {
        mainRenderer.open(player);
    }

    public void openVeteranMenu(Player player) {
        veteranRenderer.open(player);
    }

    public void openShopMenu(Player player) {
        shopRenderer.open(player);
    }

    /** 翻页：页码先推进再局部重绘（越界由 Pagination 夹取回写）。 */
    public void changePage(Player player, String menuName, int delta) {
        if (delta == 0) {
            return;
        }
        MenuConfig config = loader.get(menuName);
        if (config == null || config.getDynamicSlots().isEmpty()) {
            return;
        }
        int current = pages.get(player.getUniqueId(), menuName);
        int next = Math.max(1, current + delta);
        if (next == current) {
            return;
        }
        pages.set(player.getUniqueId(), menuName, next);
        switch (menuName) {
            case MenuNames.VETERAN -> veteranRenderer.refresh(player);
            case MenuNames.SHOP -> shopRenderer.refresh(player);
            default -> { }
        }
    }

    /** 翻页上限校验由 Pagination 在渲染时夹取；此方法仅供旧调用兼容。 */
    public int getPage(Player player, String menuName) {
        return pages.get(player.getUniqueId(), menuName);
    }

    public void reloadMenus() {
        loader.reload();
    }

    /** onDisable 调用：清理全部 GUI 状态。 */
    public void shutdown() {
        sessions.clearAll();
        pages.clearAll();
        cooldowns.clearAll();
        inputService.clearAll();
    }

    public String currentMenuName(Player player) {
        MenuSessionStore.Session session = sessions.get(player.getUniqueId());
        return session == null ? "none" : session.menuName();
    }

    public MenuConfigLoader getLoader() {
        return loader;
    }

    public MenuSessionStore getSessions() {
        return sessions;
    }

    public GuiPageStore getPages() {
        return pages;
    }

    public ButtonCooldowns getCooldowns() {
        return cooldowns;
    }

    public ActionPdc getPdc() {
        return pdc;
    }

    public MenuActionRegistry getRegistry() {
        return registry;
    }

    public MenuClickListener getClickListener() {
        return clickListener;
    }

    public MenuInputService getInputService() {
        return inputService;
    }

    public PlaceholderResolver getPlaceholderResolver() {
        return placeholderResolver;
    }
}
