package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.database.DatabaseManager;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.GuiPageStore;
import com.alinvite.gui.MenuConfig;
import com.alinvite.gui.MenuItem;
import com.alinvite.gui.MenuNames;
import com.alinvite.gui.MenuSessionStore;
import com.alinvite.gui.Pagination;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员查看指定玩家返利到账/领取操作记录的菜单。 */
public class AdminRebateHistoryRenderer extends BaseMenuRenderer<AdminRebateHistoryRenderer.HistoryData> {
    private final Map<UUID, Boolean> claimViewToggle = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> viewerTargets = new ConcurrentHashMap<>();
    private final Map<UUID, String> viewerTargetNames = new ConcurrentHashMap<>();

    public record HistoryData(RenderContext context, List<DatabaseManager.RebateRecord> records) {}

    public AdminRebateHistoryRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                      ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override protected String menuName() { return MenuNames.ADMIN_REBATE_HISTORY; }

    public void open(Player admin, UUID targetUuid, String targetName) {
        UUID viewer = admin.getUniqueId();
        UUID previous = viewerTargets.put(viewer, targetUuid);
        viewerTargetNames.put(viewer, targetName);
        if (previous == null || !previous.equals(targetUuid)) pages.set(viewer, menuName(), 1);
        open(admin);
    }

    public void toggleView(Player admin) {
        UUID uuid = admin.getUniqueId();
        claimViewToggle.put(uuid, !Boolean.TRUE.equals(claimViewToggle.get(uuid)));
        refresh(admin);
    }

    public void clearPlayerState(UUID uuid) {
        claimViewToggle.remove(uuid);
        viewerTargets.remove(uuid);
        viewerTargetNames.remove(uuid);
    }

    public void clearAllPlayerState() {
        claimViewToggle.clear();
        viewerTargets.clear();
        viewerTargetNames.clear();
    }

    @Override protected HistoryData loadData(Player admin) {
        UUID targetUuid = viewerTargets.get(admin.getUniqueId());
        String targetName = viewerTargetNames.getOrDefault(admin.getUniqueId(), "Unknown");
        RenderContext context = new RenderContext(admin).add("target_name", targetName);
        if (targetUuid == null) return new HistoryData(context, List.of());
        context.add("total_rebate", plugin.getPlaceholderResolver().getTotalRebateSync(targetUuid));
        context.add("unclaimed_rebate", formatAmount(plugin.getDatabaseManager().getUnclaimedRebateSync(targetUuid)));
        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && target.isOnline()) context.add("rebate_rate", plugin.getPointsRebateManager().getRebateRateDisplay(target));
        return new HistoryData(context, plugin.getDatabaseManager().getRebateRecordsSync(targetUuid, null, 200));
    }

    @Override protected RenderContext contextOf(HistoryData data) { return data.context(); }

    @Override protected void fillDynamic(Player admin, MenuConfig config, Inventory inventory, HistoryData data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) return;
        UUID viewer = admin.getUniqueId();
        boolean claims = Boolean.TRUE.equals(claimViewToggle.get(viewer));
        List<DatabaseManager.RebateRecord> filtered = data.records().stream()
            .filter(r -> claims == "claim".equals(r.type())).toList();
        Map<UUID, Integer> view = new HashMap<>(); view.put(viewer, page);
        Pagination.Page<DatabaseManager.RebateRecord> result = Pagination.page(filtered, view, viewer, slots.size());
        pages.set(viewer, menuName(), result.currentPage());
        RenderContext pageContext = data.context().copy()
            .add("page", String.valueOf(result.currentPage()))
            .add("total_pages", String.valueOf(result.totalPages()))
            .add("prev_page_hint", result.currentPage() <= 1 ? langRaw("menu.page.first_page") : langRaw("menu.page.prev_hint"))
            .add("next_page_hint", result.currentPage() >= result.totalPages() ? langRaw("menu.page.last_page") : langRaw("menu.page.next_hint"))
            .add("view_name", claims ? langRaw("menu.rebate.view_claims") : langRaw("menu.rebate.view_rebates"));
        MenuItem toggle = config.getItems().get('A');
        if (toggle != null) for (int slot : config.slotsOf('A')) setItemSafe(inventory, slot, MenuItems.build(plugin, pdc(), toggle, toggle.getState(claims ? "claim" : null), pageContext));
        MenuItem recordItem = config.getItems().get('R');
        if (recordItem == null) return;
        MenuItem.StateStyle state = recordItem.getState(claims ? "claim" : null);
        var format = java.time.format.DateTimeFormatter.ofPattern(langRaw("menu.rebate.date_format"), java.util.Locale.CHINA);
        var zone = java.util.TimeZone.getTimeZone(plugin.getConfigManager().getTimeZone()).toZoneId();
        for (int i = 0; i < result.entries().size() && i < slots.size(); i++) {
            DatabaseManager.RebateRecord record = result.entries().get(i);
            RenderContext item = pageContext.copy()
                .add("record_time", format.format(java.time.Instant.ofEpochMilli(record.createdAt()).atZone(zone)))
                .add("record_value", formatAmount(record.amount()))
                .add("record_text", langRaw("claim".equals(record.type()) ? "menu.rebate.record_text_claim" : "menu.rebate.record_text").replace("{value}", formatAmount(record.amount())));
            if (record.sourceName() != null && !record.sourceName().isBlank()) item.add("record_source", record.sourceName());
            setItemSafe(inventory, slots.get(i), MenuItems.build(plugin, pdc(), recordItem, state, item));
        }
        if (result.entries().isEmpty()) {
            RenderContext empty = pageContext.copy().add("record_time", langRaw(claims ? "menu.rebate.empty_title_claim" : "menu.rebate.empty_title")).add("record_text", langRaw(claims ? "menu.rebate.empty_claim" : "menu.rebate.empty"));
            setItemSafe(inventory, slots.get(0), MenuItems.build(plugin, pdc(), recordItem, state, empty));
        }
        renderPagingButtons(config, inventory, pageContext);
    }

    private String formatAmount(double amount) { return amount == Math.floor(amount) && !Double.isInfinite(amount) ? String.valueOf((long) amount) : String.format(java.util.Locale.ROOT, "%.2f", amount); }
    private String langRaw(String key) { return plugin.getConfigManager().getMessageRaw(key); }
}