package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.database.ledger.AtomicInviteLedger;
import com.alinvite.gui.*;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owner-scoped pending invitations. Cancellation uses the exact reservation displayed. */
public final class PendingInvitesMenuRenderer extends BaseMenuRenderer<PendingInvitesMenuRenderer.Data> {
    record Data(Player player, List<AtomicInviteLedger.Pending> rows) {}
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        .withZone(ZoneId.systemDefault());

    public PendingInvitesMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                      ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }
    @Override protected String menuName() { return MenuNames.PENDING; }
    @Override protected boolean canRender(Player player) { return player.hasPermission("alinvite.use"); }
    @Override public void open(Player player) {
        if (!canRender(player)) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission", player)); return;
        }
        super.open(player);
    }
    @Override protected Data loadData(Player player) {
        plugin.getDatabaseManager().expirePendingInvitesSync();
        return new Data(player, plugin.getDatabaseManager().getAtomicLedger().listPending(player.getUniqueId(), 1000));
    }
    @Override protected RenderContext contextOf(Data data) {
        double hours = plugin.getConfigManager().getConfig().getDouble("pre_invites.expiry_hours", 20.0);
        return new RenderContext(data.player()).add("pending_count", Integer.toString(data.rows().size()))
            .add("pending_duration", hours <= 0 ? text("never") : String.format(java.util.Locale.ROOT, "%.1fh", hours));
    }
    @Override protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, Data data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) return;
        var result = Pagination.page(data.rows(), new HashMap<>(Map.of(player.getUniqueId(), page)), player.getUniqueId(), slots.size());
        pages.set(player.getUniqueId(), menuName(), result.currentPage());
        slots.forEach(slot -> inventory.setItem(slot, null));
        RenderContext context = contextOf(data).add("page", Integer.toString(result.currentPage()))
            .add("total_pages", Integer.toString(result.totalPages()));
        renderStatic(player, config, inventory, context);
        if (result.entries().isEmpty()) {
            MenuItem empty = new MenuItem();
            empty.setMaterial("GRAY_DYE"); empty.setName(text("none"));
            setItemSafe(inventory, slots.getFirst(), MenuItems.build(plugin, pdc(), empty, null, context));
            return;
        }
        for (int i = 0; i < result.entries().size(); i++) {
            int slot = slots.get(i);
            var row = result.entries().get(i);
            MenuItem template = config.getItems().get(String.valueOf(config.getShape().get(slot / 9).charAt(slot % 9)));
            long remaining = row.expiresAt() == null ? -1 : row.expiresAt() - System.currentTimeMillis();
            RenderContext rowContext = context.copy().add("pending_target", row.displayName())
                .add("pending_remaining", row.expiresAt() == null ? text("never") : remaining <= 0 ? text("expired")
                    : String.format(java.util.Locale.ROOT, "%.1fh", Math.max(0.1, remaining / 3600000.0)))
                .add("pending_expiry", row.expiresAt() == null ? text("never") : DATE.format(Instant.ofEpochMilli(row.expiresAt())))
                .add("pending_action", row.targetName() + "|" + java.util.Objects.toString(row.reservationId(), "-"));
            setItemSafe(inventory, slot, MenuItems.build(plugin, pdc(), template, null, rowContext));
        }
    }
    private String text(String key) { return plugin.getConfigManager().getMessageRaw("pending_menu." + key); }
    @Override protected void onLoadFailure(Player player, MenuSessionStore.Session expected, RuntimeException error) {
        super.onLoadFailure(player, expected, error);
        scheduler.runAtPlayer(player, () -> {
            if (player.isOnline() && sessions.get(player.getUniqueId()) == expected)
                player.sendMessage(plugin.getConfigManager().getMessage("pending_menu.failed", player));
        });
    }
}
