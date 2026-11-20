package com.alinvite.gui;

import com.alinvite.ALInvite;
import com.alinvite.gui.render.*;
import com.alinvite.utils.PlaceholderResolver;
import org.bukkit.entity.Player;

/** Player menu navigation, sessions and shared action routing. */
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
    private final MainMenuRenderer mainRenderer;
    private final VeteranMenuRenderer veteranRenderer;
    private final ShopMenuRenderer shopRenderer;
    private final RebateHistoryRenderer rebateHistoryRenderer;
    private final AdminRebateHistoryRenderer adminRebateHistoryRenderer;
    private final InviteCodesMenuRenderer codesRenderer;
    private final PendingInvitesMenuRenderer pendingRenderer;
    private final InviteDeliveriesMenuRenderer deliveriesRenderer;
    private final PendingCancellation cancellation;

    public MenuManager(ALInvite plugin) {
        this.plugin = plugin;
        this.loader = new MenuConfigLoader(plugin);
        loader.loadAll();
        this.pdc = new ActionPdc(plugin);
        this.registry = new MenuActionRegistry(plugin);
        this.clickListener = new MenuClickListener(plugin, this);
        this.inputService = new MenuInputService(plugin);
        this.placeholderResolver = new PlaceholderResolver(plugin);
        var scheduler = plugin.getScheduler();
        this.mainRenderer = new MainMenuRenderer(plugin, sessions, pages, scheduler, pdc);
        this.veteranRenderer = new VeteranMenuRenderer(plugin, sessions, pages, scheduler, pdc);
        this.shopRenderer = new ShopMenuRenderer(plugin, sessions, pages, scheduler, pdc);
        this.rebateHistoryRenderer = new RebateHistoryRenderer(plugin, sessions, pages, scheduler, pdc);
        this.adminRebateHistoryRenderer = new AdminRebateHistoryRenderer(plugin, sessions, pages, scheduler, pdc);
        this.codesRenderer = new InviteCodesMenuRenderer(plugin, sessions, pages, scheduler, pdc);
        this.pendingRenderer = new PendingInvitesMenuRenderer(plugin, sessions, pages, scheduler, pdc);
        this.deliveriesRenderer = new InviteDeliveriesMenuRenderer(plugin, sessions, pages, scheduler, pdc);
        this.cancellation = new PendingCancellation(plugin, this);
    }

    public void openAdminRebateHistoryMenu(Player admin, java.util.UUID targetUuid, String targetName) {
        inputService.clear(admin.getUniqueId());
        adminRebateHistoryRenderer.open(admin, targetUuid, targetName);
    }
    public void toggleAdminRebateView(Player admin) { adminRebateHistoryRenderer.toggleView(admin); }
    public void clearAdminRebateState(java.util.UUID uuid) { adminRebateHistoryRenderer.clearPlayerState(uuid); }
    public void openRebateHistoryMenu(Player player) {
        inputService.clear(player.getUniqueId());
        rebateHistoryRenderer.open(player);
    }
    public void toggleRebateView(Player player) { rebateHistoryRenderer.toggleView(player); }

    public void openByName(Player player, String menuName) {
        if (menuName == null) return;
        switch (menuName) {
            case MenuNames.MAIN -> openMainMenu(player);
            case MenuNames.VETERAN -> openVeteranMenu(player);
            case MenuNames.SHOP -> openShopMenu(player);
            case MenuNames.REBATE_HISTORY -> openRebateHistoryMenu(player);
            case MenuNames.CODES -> openInviteCodesMenu(player);
            case MenuNames.PENDING -> openPendingInvitesMenu(player);
            case MenuNames.DELIVERIES -> openDeliveriesMenu(player);
            default -> { }
        }
    }
    public String getParentMenu(String menuName) {
        if (menuName == null) return null;
        return switch (menuName) {
            case MenuNames.VETERAN, MenuNames.REBATE_HISTORY, MenuNames.CODES,
                 MenuNames.PENDING, MenuNames.DELIVERIES -> MenuNames.MAIN;
            case MenuNames.SHOP -> MenuNames.VETERAN;
            default -> null;
        };
    }

    public void openMainMenu(Player player) {
        inputService.clear(player.getUniqueId()); mainRenderer.open(player);
    }
    public void openVeteranMenu(Player player) {
        inputService.clear(player.getUniqueId()); veteranRenderer.open(player);
    }
    public void openShopMenu(Player player) {
        inputService.clear(player.getUniqueId()); shopRenderer.open(player);
    }
    public void openInviteCodesMenu(Player player) {
        inputService.clear(player.getUniqueId()); codesRenderer.open(player);
    }
    public void openPendingInvitesMenu(Player player) {
        inputService.clear(player.getUniqueId()); pendingRenderer.open(player);
    }
    public void openDeliveriesMenu(Player player) {
        inputService.clear(player.getUniqueId()); deliveriesRenderer.open(player);
    }
    public void refreshPendingInvitesMenu(Player player) { pendingRenderer.refresh(player); }
    public void refreshDeliveriesMenu(Player player) { deliveriesRenderer.refresh(player); }
    public void refreshInviteCodesMenu(Player player) { codesRenderer.refresh(player); }
    public void shareInviteCode(Player player, String code) { codesRenderer.share(player, code); }
    public void cancelPendingInvite(Player player, String key) { cancellation.request(player, key); }
    public void confirmPendingCancellation(Player player) { cancellation.confirm(player); }
    public void clearPendingCancellation(java.util.UUID player, org.bukkit.inventory.Inventory inventory) { cancellation.clear(player, inventory); }
    public void clearPendingCancellation(java.util.UUID player) { cancellation.clear(player); }

    public void changePage(Player player, String menuName, int delta) {
        if (delta == 0) return;
        MenuConfig config = loader.get(menuName);
        if (config == null || config.getDynamicSlots().isEmpty()) return;
        int current = pages.get(player.getUniqueId(), menuName);
        int next = Math.max(1, current + delta);
        if (next == current) return;
        pages.set(player.getUniqueId(), menuName, next);
        switch (menuName) {
            case MenuNames.VETERAN -> veteranRenderer.refresh(player);
            case MenuNames.SHOP -> shopRenderer.refresh(player);
            case MenuNames.CODES -> codesRenderer.refresh(player);
            case MenuNames.PENDING -> pendingRenderer.refresh(player);
            case MenuNames.DELIVERIES -> deliveriesRenderer.refresh(player);
            default -> { }
        }
    }
    public int getPage(Player player, String menuName) { return pages.get(player.getUniqueId(), menuName); }
    public void reloadMenus() { loader.reload(); }
    public void shutdown() {
        sessions.clearAll(); pages.clearAll(); cooldowns.clearAll(); inputService.clearAll();
        cancellation.clearAll(); adminRebateHistoryRenderer.clearAllPlayerState();
    }
    public String currentMenuName(Player player) {
        var session = sessions.get(player.getUniqueId());
        return session == null ? "none" : session.menuName();
    }
    public MenuConfigLoader getLoader() { return loader; }
    public MenuSessionStore getSessions() { return sessions; }
    public GuiPageStore getPages() { return pages; }
    public ButtonCooldowns getCooldowns() { return cooldowns; }
    public ActionPdc getPdc() { return pdc; }
    public MenuActionRegistry getRegistry() { return registry; }
    public MenuClickListener getClickListener() { return clickListener; }
    public MenuInputService getInputService() { return inputService; }
    public PlaceholderResolver getPlaceholderResolver() { return placeholderResolver; }
}
