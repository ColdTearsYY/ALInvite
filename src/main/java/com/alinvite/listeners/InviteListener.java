package com.alinvite.listeners;

import com.alinvite.ALInvite;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家加入：异步预取邀请码/补发待领奖励/礼包过期检查。
 * 全链无 join；奖励发放由 RewardService 切回实体线程。
 */
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

        if (!player.hasPermission(veteranPerm)) {
            // 没有 veteran 权限 → 走自动老玩家检查
            if (plugin.getAutoVeteranManager() != null && plugin.getAutoVeteranManager().isEnabled()) {
                plugin.getAutoVeteranManager().onPlayerJoin(player);
            }
            return;
        }

        // 已有 veteran 权限 → 预热数据、补发待领取里程碑、检查礼包过期
        plugin.getInviteManager().ensureInviteCode(player.getUniqueId())
            .thenCompose(code -> plugin.getMilestoneManager().checkPendingMilestones(player))
            .thenCompose(v -> plugin.checkGiftExpiration(player))
            .exceptionally(throwable -> {
                plugin.getLogger().warning("玩家加入处理失败 (" + player.getName() + "): " + throwable.getMessage());
                return null;
            });
    }
}
