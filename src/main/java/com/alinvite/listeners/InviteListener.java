package com.alinvite.listeners;

import com.alinvite.ALInvite;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Handles veteran warm-up, existing bindings, pre-invites, hidden registrations, and gate. */
public class InviteListener implements Listener {

    private final ALInvite plugin;

    public InviteListener(ALInvite plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String veteranPerm = plugin.getConfigManager().getConfig()
            .getString("invite_code.veteran_permission", "alinvite.veteran");

        if (player.hasPermission(veteranPerm)) {
            warmUpVeteran(player);
            return;
        }

        plugin.getScheduler().runAtPlayer(player, () ->
            plugin.getBindingGateManager().lock(player, false));

        // 已绑定玩家优先放行，不消耗开放注册池。
        plugin.getDatabaseManager().hasUsedInviteCode(player.getUniqueId()).thenCompose(bound -> {
            if (Boolean.TRUE.equals(bound)) {
                return java.util.concurrent.CompletableFuture.completedFuture(AccessResult.EXISTING_BINDING);
            }
            // /alinvite add 预邀请优先，不消耗开放注册池。
            return plugin.getInviteManager().claimPendingInvite(player).thenCompose(pending -> {
                if (pending != null && pending.success) {
                    return java.util.concurrent.CompletableFuture.completedFuture(AccessResult.PRE_INVITE);
                }
                // 普通新人自动领取一个隐藏注册码；无剩余码则继续现有 60 秒绑定门禁。
                return plugin.getDatabaseManager().claimRegistrationSlot(
                    player.getUniqueId(), player.getName()).thenApply(claimed ->
                        claimed ? AccessResult.HIDDEN_REGISTRATION : AccessResult.NONE);
            });
        }).thenAccept(result -> {
            if (result != AccessResult.NONE) {
                plugin.getBindingGateManager().unlock(player.getUniqueId());
                if (result == AccessResult.HIDDEN_REGISTRATION) {
                    plugin.getScheduler().runAtPlayer(player, () ->
                        player.sendMessage(plugin.getConfigManager()
                            .getMessage("binding_gate.registration_success", player)));
                }
                scheduleVeteranCheck(player);
                return;
            }
            plugin.getScheduler().runAtPlayer(player, () ->
                plugin.getBindingGateManager().enforceLock(player));
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("玩家注册资格检查失败 (" + player.getName()
                + "): " + throwable.getMessage());
            plugin.getScheduler().runAtPlayer(player, () ->
                plugin.getBindingGateManager().enforceLock(player));
            return null;
        });
    }

    private void scheduleVeteranCheck(Player player) {
        if (plugin.getAutoVeteranManager() != null
                && plugin.getAutoVeteranManager().isEnabled()) {
            plugin.getAutoVeteranManager().onPlayerJoin(player);
        }
    }

    private void warmUpVeteran(Player player) {
        plugin.getInviteManager().ensureInviteCode(player.getUniqueId())
            .thenCompose(code -> plugin.getMilestoneManager().checkPendingMilestones(player))
            .thenCompose(v -> plugin.checkGiftExpiration(player))
            .exceptionally(throwable -> {
                plugin.getLogger().warning("玩家加入处理失败 (" + player.getName()
                    + "): " + throwable.getMessage());
                return null;
            });
    }

    private enum AccessResult {
        NONE,
        EXISTING_BINDING,
        PRE_INVITE,
        HIDDEN_REGISTRATION
    }
}
