package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.gui.*;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.function.Function;

public class MainMenuRenderer extends BaseMenuRenderer<RenderContext> {
    private static final List<String> LEGACY_BUTTONS = List.of("O", "P", "D", "H", "G", "C");

    public MainMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                            ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override protected String menuName() { return MenuNames.MAIN; }

    @Override protected RenderContext loadData(Player player) {
        plugin.getPlaytimeManager().refresh(player.getUniqueId()).join();
        var context = plugin.getPlaceholderResolver().renderContext(player, false);
        var quota = plugin.getDatabaseManager().getQuotaStatusSync(player.getUniqueId());
        Function<String, String> text = plugin.getConfigManager()::getMessageRaw;
        context.add("quota_group", quota.group()).add("quota_reserved", Integer.toString(quota.reserved()))
            .add("quota_capacity", quota.capacity());
        if (!"-".equals(quota.next()) && !"full".equals(quota.state())) {
            context.add("quota_progress", text.apply("player_menu.next")
                .replace("{next}", quota.next()).replace("{remaining}", quota.remaining()));
        }
        if (!"progress".equals(quota.state())) context.add("quota_notice", text.apply("operations." + quota.state()));
        return context;
    }

    @Override protected RenderContext contextOf(RenderContext data) { return data; }

    @Override protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, RenderContext data, int page) {
        var settings = plugin.getConfigManager();
        data.add("rebate_rate", plugin.getPointsRebateManager().getRebateRateDisplay(player));
        String contributionPermission = settings.getConfig().getString("contribution_rebate.permission", "alinvite.rebate.contribution");
        if (player.hasPermission("alinvite.use") && player.hasPermission(contributionPermission)) {
            data.add("contribution_info", settings.getMessageRaw("player_menu.contribution")
                .replace("{amount}", data.strings().getOrDefault("contribution", "—")));
        }
        boolean bound = settings.getMessageRaw("player_menu.bound").equals(data.strings().get("bind_status"));
        data.add("bind_hint", settings.getMessageRaw(bound ? "player_menu.bind_done" : "player_menu.bind_click"));
        data.add("bind_complete", Boolean.toString(bound));
        data.add("bind_title", settings.getMessageRaw(bound ? "player_menu.bind_title_done" : "player_menu.bind_title_input"));
        renderStatic(player, config, inventory, data);
        MenuConfig builtin = plugin.getMenuConfigLoader().getBuiltinMainMenu();
        if (builtin != null) {
            for (String key : LEGACY_BUTTONS) {
                MenuItems.ensureMainEntry(plugin, pdc(), config, inventory, builtin.getItems().get(key), data);
            }
        }
    }

    @Override protected void onLoadFailure(Player player, MenuSessionStore.Session expected, RuntimeException error) {
        super.onLoadFailure(player, expected, error);
        scheduler.runAtPlayer(player, () -> {
            if (player.isOnline() && sessions.get(player.getUniqueId()) == expected)
                player.sendMessage(plugin.getConfigManager().getMessage("player_menu.failed", player));
        });
    }
}
