package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.gui.*;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Paged AVAILABLE inventory; opening/refreshing/sharing never mints or reserves a code. */
public final class InviteCodesMenuRenderer extends BaseMenuRenderer<RenderContext> {
    public InviteCodesMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                                   ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override protected String menuName() { return MenuNames.CODES; }

    @Override public void open(Player player) {
        if (!player.hasPermission("alinvite.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }
        super.open(player);
    }

    @Override protected RenderContext loadData(Player player) {
        RenderContext context = new RenderContext(player);
        InviteCodeMenuSupport.populate(context,
            plugin.getDatabaseManager().listAvailableCodesSync(player.getUniqueId()),
            plugin.getConfigManager()::getMessageRaw);
        return context;
    }

    @Override protected void onLoadFailure(Player player, MenuSessionStore.Session expected, RuntimeException error) {
        super.onLoadFailure(player, expected, error);
        scheduler.runAtPlayer(player, () -> {
            if (player.isOnline() && sessions.get(player.getUniqueId()) == expected)
                player.sendMessage(plugin.getConfigManager().getMessage("codes_menu.failed"));
        });
    }

    @Override protected RenderContext contextOf(RenderContext data) { return data; }

    @Override protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, RenderContext data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) return;
        var pageMap = new HashMap<UUID, Integer>();
        pageMap.put(player.getUniqueId(), page);
        var result = Pagination.page(data.lists().get("available_invite_codes"), pageMap, player.getUniqueId(), slots.size());
        pages.set(player.getUniqueId(), menuName(), result.currentPage());
        RenderContext context = data.copy()
            .add("page", Integer.toString(result.currentPage()))
            .add("total_pages", Integer.toString(result.totalPages()));
        // Clear the entire old page: a used/reserved code must not remain in an unused slot.
        slots.forEach(slot -> inventory.setItem(slot, null));
        MenuItem template = config.getItems().values().stream().filter(MenuItem::isDynamic).findFirst().orElse(null);
        if (template != null) {
            if (result.entries().isEmpty()) {
                MenuItem empty = new MenuItem();
                var style = template.getState("empty");
                empty.setMaterial(style != null && style.hasMaterial() ? style.getMaterial() : "GRAY_DYE");
                empty.setName(style != null && style.hasName() ? style.getName() : plugin.getConfigManager().getMessageRaw("codes_menu.none"));
                empty.setLore(style != null && style.hasLore() ? style.getLore() : List.of());
                setItemSafe(inventory, slots.get(0), MenuItems.build(plugin, pdc(), empty, null, context));
            } else {
                for (int i = 0; i < result.entries().size(); i++) {
                    setItemSafe(inventory, slots.get(i), MenuItems.build(plugin, pdc(), template, null,
                        context.copy().add("current_code", result.entries().get(i))));
                }
            }
        }
        // Redraw summary + navigation, not only the dynamic list, when stock changes.
        for (var entry : config.getItems().entrySet()) {
            if (entry.getValue().isDynamic()) continue;
            for (int slot : config.slotsOf(entry.getKey().charAt(0))) {
                setItemSafe(inventory, slot, MenuItems.build(plugin, pdc(), entry.getValue(), null, context));
            }
        }
    }

    public void share(Player player, String code) {
        if (!player.hasPermission("alinvite.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }
        var session = sessions.get(player.getUniqueId());
        if (session == null || !menuName().equals(session.menuName())) return;
        // PDC belongs to a snapshot; validate both current ownership and availability again.
        plugin.getInviteManager().listAvailableCodes(player.getUniqueId()).thenAccept(codes ->
            scheduler.runAtPlayer(player, () -> {
                if (!player.isOnline() || !player.hasPermission("alinvite.use")
                        || sessions.get(player.getUniqueId()) != session) return;
                if (!codes.contains(code)) {
                    player.sendMessage(plugin.getConfigManager().getMessage("codes_menu.stale"));
                    refresh(player);
                    return;
                }
                var serializer = LegacyComponentSerializer.legacySection();
                var message = serializer.deserialize(plugin.getConfigManager().getMessageRaw("codes_menu.share")
                    .replace("{code}", code))
                    .clickEvent(ClickEvent.copyToClipboard(code))
                    .hoverEvent(serializer.deserialize(plugin.getConfigManager().getMessageRaw("codes_menu.copy_hint")));
                player.closeInventory();
                player.sendMessage(message);
            })).exceptionally(error -> {
                plugin.getLogger().warning("读取邀请码分享信息失败: " + error.getMessage());
                scheduler.runAtPlayer(player, () -> {
                    if (player.isOnline() && sessions.get(player.getUniqueId()) == session)
                        player.sendMessage(plugin.getConfigManager().getMessage("codes_menu.failed"));
                });
                return null;
            });
    }
}
