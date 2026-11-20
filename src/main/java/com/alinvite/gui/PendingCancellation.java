package com.alinvite.gui;

import com.alinvite.ALInvite;
import com.alinvite.database.ledger.AtomicInviteLedger;
import com.alinvite.gui.render.MenuItems;
import com.alinvite.gui.render.RenderContext;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A confirmation is tied to an exact reservation and an exact open inventory. */
final class PendingCancellation {
    static final String MENU = "confirm_pending_cancel";
    private record Approval(AtomicInviteLedger.Pending row, MenuSessionStore.Session session, long deadline) {}
    private final Map<UUID, Approval> approvals = new ConcurrentHashMap<>();
    private final ALInvite plugin;
    private final MenuManager menus;

    PendingCancellation(ALInvite plugin, MenuManager menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    void request(Player player, String key) {
        var expected = menus.getSessions().get(player.getUniqueId());
        if (expected == null || !MenuNames.PENDING.equals(expected.menuName()) || !player.hasPermission("alinvite.use")) return;
        int split = key == null ? -1 : key.indexOf('|');
        if (split <= 0) return;
        String target = key.substring(0, split);
        Object navigation = menus.getSessions().beginLoad(player.getUniqueId());
        AsyncPool.supply(() -> plugin.getDatabaseManager().getAtomicLedger().getPending(target)).whenComplete((row, error) ->
            plugin.getScheduler().runAtPlayer(player, () -> {
                if (!player.isOnline() || menus.getSessions().get(player.getUniqueId()) != expected
                        || !menus.getSessions().isLatestLoad(player.getUniqueId(), navigation)
                        || !player.hasPermission("alinvite.use")) return;
                if (error != null) { message(player, "failed"); return; }
                if (!matches(row, player.getUniqueId(), key)) {
                    message(player, "stale"); menus.refreshPendingInvitesMenu(player); return;
                }
                var holder = new InviteMenuHolder(MENU);
                var inventory = Bukkit.createInventory(holder, 27, text("confirm_title"));
                holder.setInventory(inventory);
                var context = new RenderContext(player).add("pending_target", row.displayName());
                MenuItem confirm = item("LIME_DYE", "confirm_yes", "confirm_cancel_pending");
                confirm.setLore(List.of(text("confirm_target"), text("confirm_return")));
                inventory.setItem(11, MenuItems.build(plugin, menus.getPdc(), confirm, null, context));
                inventory.setItem(15, MenuItems.build(plugin, menus.getPdc(), item("RED_DYE", "confirm_no", "open_pending"), null, context));
                menus.getSessions().track(player.getUniqueId(), MENU, inventory);
                approvals.put(player.getUniqueId(), new Approval(row, menus.getSessions().get(player.getUniqueId()), System.currentTimeMillis() + 30_000L));
                player.openInventory(inventory);
            }));
    }

    void confirm(Player player) {
        Approval approval = approvals.remove(player.getUniqueId());
        if (approval == null || !player.hasPermission("alinvite.use") || !player.isOnline()
                || menus.getSessions().get(player.getUniqueId()) != approval.session()) return;
        if (approval.deadline() < System.currentTimeMillis()) {
            message(player, "stale"); menus.openPendingInvitesMenu(player); return;
        }
        // Removing the approval before IO makes repeated confirm clicks harmless.
        plugin.getDatabaseManager().releasePendingInvite(approval.row()).whenComplete((released, error) ->
            plugin.getScheduler().runAtPlayer(player, () -> {
                if (!player.isOnline() || menus.getSessions().get(player.getUniqueId()) != approval.session()) return;
                message(player, error != null ? "failed" : Boolean.TRUE.equals(released) ? "cancelled" : "stale");
                if (player.hasPermission("alinvite.use")) menus.openPendingInvitesMenu(player);
            }));
    }

    static String key(AtomicInviteLedger.Pending row) {
        return row.targetName() + "|" + java.util.Objects.toString(row.reservationId(), "-");
    }

    static boolean matches(AtomicInviteLedger.Pending row, UUID owner, String key) {
        return row != null && owner != null && owner.equals(row.inviterUuid()) && key(row).equals(key);
    }

    void clear(UUID player) { approvals.remove(player); }
    void clearAll() { approvals.clear(); }
    void clear(UUID player, org.bukkit.inventory.Inventory inventory) {
        approvals.computeIfPresent(player, (uuid, approval) -> approval.session().inventory() == inventory ? null : approval);
    }

    private MenuItem item(String material, String name, String action) {
        MenuItem item = new MenuItem();
        item.setMaterial(material);
        item.setName(text(name));
        item.getTriggers().put("left", List.of(action));
        return item;
    }
    private String text(String key) { return plugin.getConfigManager().getMessageRaw("pending_menu." + key); }
    private void message(Player player, String key) { player.sendMessage(plugin.getConfigManager().getMessage("pending_menu." + key, player)); }
}
