package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.database.delivery.DeliveryDao;
import com.alinvite.gui.*;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Lists the player's own deliveries without exposing job payloads or administrative actions. */
public final class InviteDeliveriesMenuRenderer extends BaseMenuRenderer<List<DeliveryDao.Work>> {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        .withZone(ZoneId.systemDefault());

    public InviteDeliveriesMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                       ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override protected String menuName() { return MenuNames.DELIVERIES; }
    @Override protected boolean canRender(Player player) { return player.hasPermission("alinvite.use"); }

    @Override public void open(Player player) {
        if (!player.hasPermission("alinvite.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission", player));
            return;
        }
        super.open(player);
    }

    @Override protected List<DeliveryDao.Work> loadData(Player player) {
        try {
            return plugin.getDatabaseManager().getDeliveryDao().list(player.getUniqueId(), 100);
        } catch (SQLException error) {
            throw new IllegalStateException("读取奖励记录失败", error);
        }
    }

    @Override protected RenderContext contextOf(List<DeliveryDao.Work> data) { return new RenderContext(null); }

    @Override protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, List<DeliveryDao.Work> data, int page) {
        var slots = config.getDynamicSlots();
        if (slots.isEmpty()) return;
        var result = Pagination.page(data, new HashMap<>(Map.of(player.getUniqueId(), page)), player.getUniqueId(), slots.size());
        pages.set(player.getUniqueId(), menuName(), result.currentPage());
        slots.forEach(slot -> inventory.setItem(slot, null));
        RenderContext context = new RenderContext(player).add("page", Integer.toString(result.currentPage()))
            .add("total_pages", Integer.toString(result.totalPages()));
        renderStatic(player, config, inventory, context);
        if (result.entries().isEmpty()) {
            MenuItem empty = new MenuItem();
            empty.setMaterial("GRAY_DYE");
            empty.setName(text("no_deliveries"));
            setItemSafe(inventory, slots.getFirst(), MenuItems.build(plugin, pdc(), empty, null, context));
            return;
        }
        for (int i = 0; i < result.entries().size(); i++) {
            int slot = slots.get(i);
            var work = result.entries().get(i);
            MenuItem template = config.getItems().get(String.valueOf(config.getShape().get(slot / 9).charAt(slot % 9)));
            RenderContext row = context.copy()
                .add("delivery_kind", text("delivery_kind." + kind(work)))
                .add("delivery_state", text("delivery_state." + state(work.state())))
                .add("delivery_time", DATE.format(Instant.ofEpochMilli(work.updatedAt())));
            setItemSafe(inventory, slot, MenuItems.build(plugin, pdc(), template, null, row));
        }
    }

    static String kind(DeliveryDao.Work work) {
        return switch (work.kind()) {
            case "whitelist", "milestone" -> work.kind();
            case "reward" -> work.id().startsWith("bind:") && work.id().contains(":gift:") ? "gift" : "reward";
            default -> "other";
        };
    }

    static String state(String state) {
        return switch (state) {
            case "READY", "CLAIMED", "RUNNING", "DONE", "REVIEW" -> state;
            default -> "other";
        };
    }

    private String text(String key) { return plugin.getConfigManager().getMessageRaw("player_menu." + key); }

    @Override protected void onLoadFailure(Player player, MenuSessionStore.Session expected, RuntimeException error) {
        super.onLoadFailure(player, expected, error);
        scheduler.runAtPlayer(player, () -> {
            if (player.isOnline() && sessions.get(player.getUniqueId()) == expected)
                player.sendMessage(plugin.getConfigManager().getMessage("player_menu.failed", player));
        });
    }
}
