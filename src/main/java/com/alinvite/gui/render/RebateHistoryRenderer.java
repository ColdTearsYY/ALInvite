package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.GuiPageStore;
import com.alinvite.gui.MenuConfig;
import com.alinvite.gui.MenuItem;
import com.alinvite.gui.MenuNames;
import com.alinvite.gui.MenuSessionStore;
import com.alinvite.gui.Pagination;
import com.alinvite.database.DatabaseManager;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 返利记录菜单：展示每笔返利的发放时间与金额（时间倒序，最近 100 条）。
 * 动态条目上下文：record_time（格式化日期）、record_text（"为你提供了 X 点券返利"）、record_source（来源玩家，可选）。
 */
public class RebateHistoryRenderer extends BaseMenuRenderer<RebateHistoryRenderer.HistoryData> {

    public record HistoryData(RenderContext context, List<DatabaseManager.RebateRecord> records) {
    }

    public RebateHistoryRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                 ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override
    protected String menuName() {
        return MenuNames.REBATE_HISTORY;
    }

    @Override
    protected HistoryData loadData(Player player) {
        UUID uuid = player.getUniqueId();
        RenderContext context = plugin.getPlaceholderResolver().renderContext(player);
        List<DatabaseManager.RebateRecord> records = plugin.getDatabaseManager().getRebateRecordsSync(uuid, 100);
        return new HistoryData(context, records);
    }

    @Override
    protected RenderContext contextOf(HistoryData data) {
        return data.context();
    }

    @Override
    protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, HistoryData data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Map<UUID, Integer> pageView = new HashMap<>();
        pageView.put(uuid, page);
        Pagination.Page<DatabaseManager.RebateRecord> result =
            Pagination.page(data.records(), pageView, uuid, slots.size());
        pages.set(uuid, menuName(), result.currentPage());

        RenderContext pageContext = data.context().copy()
            .add("page", String.valueOf(result.currentPage()))
            .add("total_pages", String.valueOf(result.totalPages()))
            .add("prev_page_hint", result.currentPage() <= 1
                ? langRaw("menu.page.first_page") : langRaw("menu.page.prev_hint"))
            .add("next_page_hint", result.currentPage() >= result.totalPages()
                ? langRaw("menu.page.last_page") : langRaw("menu.page.next_hint"));

        MenuItem recordItem = config.getItems().values().stream()
            .filter(MenuItem::isDynamic)
            .findFirst()
            .orElse(null);
        if (recordItem == null) {
            return;
        }

        String datePattern = langRaw("menu.rebate.date_format");
        SimpleDateFormat format = new SimpleDateFormat(datePattern, Locale.CHINA);
        format.setTimeZone(java.util.TimeZone.getTimeZone(
            plugin.getConfigManager().getTimeZone()));
        String emptyText = langRaw("menu.rebate.empty");

        int index = 0;
        for (DatabaseManager.RebateRecord record : result.entries()) {
            if (index >= slots.size()) {
                break;
            }
            RenderContext itemContext = pageContext.copy()
                .add("record_time", format.format(record.createdAt()))
                .add("record_value", formatAmount(record.amount()))
                .add("record_text", langRaw("menu.rebate.record_text")
                    .replace("{value}", formatAmount(record.amount())));
            if (record.sourceName() != null && !record.sourceName().isBlank()) {
                itemContext.add("record_source", record.sourceName());
            }

            setItemSafe(inventory, slots.get(index),
                MenuItems.build(plugin, pdc(), recordItem, recordItem.getState(null), itemContext));
            index++;
        }

        // 空记录时用"暂无返利记录"占位第一格
        if (result.entries().isEmpty()) {
            RenderContext emptyContext = pageContext.copy()
                .add("record_time", langRaw("menu.rebate.empty_title"))
                .add("record_text", emptyText);
            setItemSafe(inventory, slots.get(0),
                MenuItems.build(plugin, pdc(), recordItem, recordItem.getState(null), emptyContext));
        }

        renderPagingButtons(config, inventory, pageContext);
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) && !Double.isInfinite(amount)
            ? String.valueOf((long) amount)
            : String.format(Locale.ROOT, "%.2f", amount);
    }

    protected String langRaw(String key) {
        return plugin.getConfigManager().getMessageRaw(key);
    }
}
