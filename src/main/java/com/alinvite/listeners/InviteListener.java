package com.alinvite.listeners;

import com.alinvite.ALInvite;
import com.alinvite.utils.SchedulerUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

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

        // 已有 veteran 权限 → 正常处理邀请码、里程碑、礼包
        SchedulerUtils.runTaskAsynchronously(plugin, () -> {
            String code = plugin.getDatabaseManager().getInviteCodeByPlayer(player.getUniqueId()).join();
            if (code == null) {
                plugin.getInviteManager().generateInviteCode(player.getUniqueId()).thenAccept(generatedCode -> {
                    // 邀请码生成完成后的处理
                });
            }
            plugin.getMilestoneManager().checkPendingMilestones(player);
            // 检查礼包是否过期
            plugin.checkGiftExpiration(player);
        });
    }
}
