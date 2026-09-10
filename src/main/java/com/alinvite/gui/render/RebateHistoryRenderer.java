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
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 返利记录菜单：展示每笔返利的发放时间与金额（时间倒序）。
 * 支持两种视图模式（A 按钮切换）：
 *   - rebate 视图：显示入池记录（充值返利到账）
 *   - claim 视图：显示领取操作记录（管理员核销 / 玩家手动领取）
 */
public class RebateHistoryRenderer extends BaseMenuRenderer<RebateHistoryRenderer.HistoryData> {

    private final Map<UUID, Boolean> claimViewToggle = new ConcurrentHashMap<>();

    public record HistoryData(RenderContext context,
                               List<DatabaseManager.RebateRecord> records) {
    }

    public RebateHistoryRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                 ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override
    protected String menuName() {
        return MenuNames.REBATE_HISTORY;
    }

    private boolean isClaimView(UUID uuid) {
        return Boolean.TRUE.equals(claimViewToggle.get(uuid));
    }

    /** 切换视图（返利到账 ↔ 领取操作）。页码归 1，两个视图的列表互不继承页码。 */
    public void toggleView(Player player) {
        UUID uuid = player.getUniqueId();
        Boolean current = claimViewToggle.get(uuid);
        claimViewToggle.put(uuid, !Boolean.TRUE.equals(current));
        pages.set(uuid, menuName(), 1);
        refresh(player);
    }

    /** 玩家退出时清理视图状态（下次打开回到返利视图）。 */
    public void clearPlayerState(UUID uuid) {
        claimViewToggle.remove(uuid);
    }

    /** 停服时清理全部视图状态。 */
    public void clearAllPlayerState() {
        claimViewToggle.clear();
    }

    @Override
    protected HistoryData loadData(Player player) {
        UUID uuid = player.getUniqueId();
        RenderContext context = plugin.getPlaceholderResolver().renderContext(player);
        List<DatabaseManager.RebateRecord> allRecords =
            plugin.getDatabaseManager().getRebateRecordsSync(uuid, null, 200);
        return new HistoryData(context, allRecords);
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
        boolean showClaims = isClaimView(uuid);

        List<DatabaseManager.RebateRecord> filtered = data.records().stream()
            .filter(r -> showClaims == "claim".equals(r.type()))
            .toList();

        Map<UUID, Integer> pageView = new HashMap<>();
        pageView.put(uuid, page);
        Pagination.Page<DatabaseManager.RebateRecord> result =
            Pagination.page(filtered, pageView, uuid, slots.size());
        pages.set(uuid, menuName(), result.currentPage());

        RenderContext pageContext = data.context().copy()
            .add("page", String.valueOf(result.currentPage()))
            .add("total_pages", String.valueOf(result.totalPages()))
            .add("prev_page_hint", result.currentPage() <= 1
                ? langRaw("menu.page.first_page") : langRaw("menu.page.prev_hint"))
            .add("next_page_hint", result.currentPage() >= result.totalPages()
                ? langRaw("menu.page.last_page") : langRaw("menu.page.next_hint"))
            .add("view_name", showClaims
                ? langRaw("menu.rebate.view_claims") : langRaw("menu.rebate.view_rebates"))
            .add("toggle_target", showClaims
                ? langRaw("menu.rebate.view_rebates") : langRaw("menu.rebate.view_claims"));

        // A 切换按钮：领取视图应用 states.claim 样式，返利视图用基础样式
        MenuItem toggleBtn = config.getItems().get("A");
        if (toggleBtn != null) {
            MenuItem.StateStyle toggleState = toggleBtn.getState(showClaims ? "claim" : null);
            for (int slot : config.slotsOf('A')) {
                setItemSafe(inventory, slot,
                    MenuItems.build(plugin, pdc(), toggleBtn, toggleState, pageContext));
            }
        }

        MenuItem recordItem = config.getItems().get("R");
        if (recordItem == null) {
            return;
        }
        MenuItem.StateStyle recordState = recordItem.getState(showClaims ? "claim" : null);

        var fmt = java.time.format.DateTimeFormatter.ofPattern(
            langRaw("menu.rebate.date_format"), Locale.CHINA);
        var tz = java.util.TimeZone.getTimeZone(plugin.getConfigManager().getTimeZone());

        int index = 0;
        for (DatabaseManager.RebateRecord record : result.entries()) {
            if (index >= slots.size()) {
                break;
            }
            RenderContext itemContext = pageContext.copy()
                .add("record_time", fmt.format(java.time.Instant.ofEpochMilli(record.createdAt())
                    .atZone(tz.toZoneId()).toLocalDateTime()))
                .add("record_value", formatAmount(record.amount()))
                .add("record_text", langRaw("claim".equals(record.type())
                    ? "menu.rebate.record_text_claim" : "menu.rebate.record_text")
                    .replace("{value}", formatAmount(record.amount())));
            if (record.sourceName() != null && !record.sourceName().isBlank()) {
                itemContext.add("record_source", record.sourceName());
            }
            setItemSafe(inventory, slots.get(index),
                MenuItems.build(plugin, pdc(), recordItem, recordState, itemContext));
            index++;
        }

        // 清空本页未占用的动态槽位（切视图或翻到较短页时移除残留条目）
        for (int i = result.entries().size(); i < slots.size(); i++) {
            setItemSafe(inventory, slots.get(i), null);
        }

        if (result.entries().isEmpty()) {
            RenderContext emptyContext = pageContext.copy()
                .add("record_time", langRaw(showClaims
                    ? "menu.rebate.empty_title_claim" : "menu.rebate.empty_title"))
                .add("record_text", langRaw(showClaims
                    ? "menu.rebate.empty_claim" : "menu.rebate.empty"));
            setItemSafe(inventory, slots.get(0),
                MenuItems.build(plugin, pdc(), recordItem, recordState, emptyContext));
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
