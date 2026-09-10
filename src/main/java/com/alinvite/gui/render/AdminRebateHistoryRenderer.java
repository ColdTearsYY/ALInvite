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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员查看指定玩家的返利记录菜单（/alinvite admin rebate <玩家>）。
 * 与 {@link RebateHistoryRenderer} 同构，差别仅在数据源是目标玩家：
 * 查看者 → 目标 的映射随会话保存在本渲染器，退出/停服时清理。
 */
public class AdminRebateHistoryRenderer extends BaseMenuRenderer<AdminRebateHistoryRenderer.HistoryData> {

    private final Map<UUID, Boolean> claimViewToggle = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> viewerTargets = new ConcurrentHashMap<>();
    private final Map<UUID, String> viewerTargetNames = new ConcurrentHashMap<>();

    public record HistoryData(RenderContext context,
                               List<DatabaseManager.RebateRecord> records) {
    }

    public AdminRebateHistoryRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                      ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override
    protected String menuName() {
        return MenuNames.ADMIN_REBATE_HISTORY;
    }

    /** 打开目标玩家的记录菜单：换目标时页码回到第 1 页。 */
    public void open(Player admin, UUID targetUuid, String targetName) {
        UUID viewer = admin.getUniqueId();
        UUID previous = viewerTargets.put(viewer, targetUuid);
        viewerTargetNames.put(viewer, targetName);
        if (previous == null || !previous.equals(targetUuid)) {
            pages.set(viewer, menuName(), 1);
        }
        open(admin);
    }

    /** 切换视图（返利到账 ↔ 领取操作）。 */
    public void toggleView(Player admin) {
        UUID uuid = admin.getUniqueId();
        Boolean current = claimViewToggle.get(uuid);
        claimViewToggle.put(uuid, !Boolean.TRUE.equals(current));
        refresh(admin);
    }

    /** 玩家退出时清理视图与目标映射。 */
    public void clearPlayerState(UUID uuid) {
        claimViewToggle.remove(uuid);
        viewerTargets.remove(uuid);
        viewerTargetNames.remove(uuid);
    }

    /** 停服时清理全部状态。 */
    public void clearAllPlayerState() {
        claimViewToggle.clear();
        viewerTargets.clear();
        viewerTargetNames.clear();
    }

    @Override
    protected HistoryData loadData(Player admin) {
        UUID viewer = admin.getUniqueId();
        UUID targetUuid = viewerTargets.get(viewer);
        String targetName = viewerTargetNames.getOrDefault(viewer, "Unknown");

        RenderContext context = new RenderContext(admin);
        context.add("target_name", targetName);
        if (targetUuid == null) {
            return new HistoryData(context, List.of());
        }
        context.add("total_rebate", plugin.getPlaceholderResolver().getTotalRebateSync(targetUuid));
        context.add("unclaimed_rebate",
            formatAmount(plugin.getDatabaseManager().getUnclaimedRebateSync(targetUuid)));
        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && target.isOnline()) {
            // 离线玩家取不到权限组比例，该行 lore 自动隐藏
            context.add("rebate_rate", plugin.getPointsRebateManager().getRebateRateDisplay(target));
        }
        List<DatabaseManager.RebateRecord> allRecords =
            plugin.getDatabaseManager().getRebateRecordsSync(targetUuid, null, 200);
        return new HistoryData(context, allRecords);
    }

    @Override
    protected RenderContext contextOf(HistoryData data) {
        return data.context();
    }

    @Override
    protected void fillDynamic(Player admin, MenuConfig config, Inventory inventory, HistoryData data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) {
            return;
        }

        UUID uuid = admin.getUniqueId();
        boolean showClaims = Boolean.TRUE.equals(claimViewToggle.get(uuid));

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
                ? langRaw("menu.rebate.view_claims") : langRaw("menu.rebate.view_rebates"));

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
            langRaw("menu.rebate.date_format"), java.util.Locale.CHINA);
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
        return amount == java.lang.Math.floor(amount) && !Double.isInfinite(amount)
            ? String.valueOf((long) amount)
            : String.format(java.util.Locale.ROOT, "%.2f", amount);
    }

    private String langRaw(String key) {
        return plugin.getConfigManager().getMessageRaw(key);
    }
}
