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
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 返利到账/领取操作记录菜单；数据库读取在实体线程外，物品回填在实体线程内。 */
public class RebateHistoryRenderer extends BaseMenuRenderer<RebateHistoryRenderer.HistoryData> {
    private final Map<UUID, Boolean> claimViewToggle = new ConcurrentHashMap<>();

    public record HistoryData(RenderContext context, List<DatabaseManager.RebateRecord> records) {}

    public RebateHistoryRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                 ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override
    protected String menuName() { return MenuNames.REBATE_HISTORY; }

    public void toggleView(Player player) {
        UUID uuid = player.getUniqueId();
        claimViewToggle.put(uuid, !Boolean.TRUE.equals(claimViewToggle.get(uuid)));
        refresh(player);
    }

    @Override
    protected HistoryData loadData(Player player) {
        UUID uuid = player.getUniqueId();
        RenderContext context = plugin.getPlaceholderResolver().renderContext(player);
        return new HistoryData(context, plugin.getDatabaseManager().getRebateRecordsSync(uuid, null, 200));
    }

    @Override
    protected RenderContext contextOf(HistoryData data) { return data.context(); }

    @Override
    protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, HistoryData data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) return;
        UUID uuid = player.getUniqueId();
        boolean claims = Boolean.TRUE.equals(claimViewToggle.get(uuid));
        List<DatabaseManager.RebateRecord> filtered = data.records().stream()
            .filter(record -> claims == "claim".equals(record.type())).toList();

        Map<UUID, Integer> view = new HashMap<>();
        view.put(uuid, page);
        Pagination.Page<DatabaseManager.RebateRecord> result = Pagination.page(filtered, view, uuid, slots.size());
        pages.set(uuid, menuName(), result.currentPage());

        RenderContext pageContext = data.context().copy()
            .add("page", String.valueOf(result.currentPage()))
            .add("total_pages", String.valueOf(result.totalPages()))
            .add("prev_page_hint", result.currentPage() <= 1 ? langRaw("menu.page.first_page") : langRaw("menu.page.prev_hint"))
            .add("next_page_hint", result.currentPage() >= result.totalPages() ? langRaw("menu.page.last_page") : langRaw("menu.page.next_hint"))
            .add("view_name", claims ? langRaw("menu.rebate.view_claims") : langRaw("menu.rebate.view_rebates"))
            .add("toggle_target", claims ? langRaw("menu.rebate.view_rebates") : langRaw("menu.rebate.view_claims"));

        MenuItem toggle = config.getItems().get("A");
        if (toggle != null) {
            for (int slot : config.slotsOf('A')) setItemSafe(inventory, slot, MenuItems.build(plugin, pdc(), toggle, null, pageContext));
        }
        MenuItem recordItem = config.getItems().values().stream().filter(MenuItem::isDynamic).findFirst().orElse(null);
        if (recordItem == null) return;

        DateTimeFormatter format = DateTimeFormatter.ofPattern(langRaw("menu.rebate.date_format"), Locale.CHINA);
        TimeZone zone = TimeZone.getTimeZone(plugin.getConfigManager().getTimeZone());
        for (int index = 0; index < result.entries().size() && index < slots.size(); index++) {
            DatabaseManager.RebateRecord record = result.entries().get(index);
            RenderContext item = pageContext.copy()
                .add("record_time", format.format(java.time.Instant.ofEpochMilli(record.createdAt()).atZone(zone.toZoneId())))
                .add("record_value", formatAmount(record.amount()))
                .add("record_text", langRaw("menu.rebate.record_text").replace("{value}", formatAmount(record.amount())));
            if (record.sourceName() != null && !record.sourceName().isBlank()) item.add("record_source", record.sourceName());
            setItemSafe(inventory, slots.get(index), MenuItems.build(plugin, pdc(), recordItem, recordItem.getState(null), item));
        }
        if (result.entries().isEmpty()) {
            RenderContext empty = pageContext.copy().add("record_time", langRaw("menu.rebate.empty_title")).add("record_text", langRaw("menu.rebate.empty"));
            setItemSafe(inventory, slots.get(0), MenuItems.build(plugin, pdc(), recordItem, recordItem.getState(null), empty));
        }
        renderPagingButtons(config, inventory, pageContext);
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) && !Double.isInfinite(amount) ? String.valueOf((long) amount) : String.format(Locale.ROOT, "%.2f", amount);
    }

    protected String langRaw(String key) { return plugin.getConfigManager().getMessageRaw(key); }
}