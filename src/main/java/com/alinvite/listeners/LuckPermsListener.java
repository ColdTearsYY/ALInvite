package com.alinvite.listeners;

import com.alinvite.ALInvite;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Method;
import java.util.UUID;

public class LuckPermsListener implements Listener {

    private final ALInvite plugin;
    private final PermissionGroupRewardListener rewardListener;
    private final boolean luckPermsEnabled;

    public LuckPermsListener(ALInvite plugin, PermissionGroupRewardListener rewardListener) {
        this.plugin = plugin;
        this.rewardListener = rewardListener;

        boolean enabled = false;
        try {
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Method getApi = luckPermsClass.getMethod("getApi");
            Object api = getApi.invoke(null);
            if (api != null) {
                Class<?> eventBusClass = Class.forName("net.luckperms.api.event.EventBus");
                Method getEventBus = luckPermsClass.getMethod("getEventBus");
                Object eventBus = getEventBus.invoke(api);

                Class<?> userUpdateClass = Class.forName("net.luckperms.api.event.user.UserDataRecalculateEvent");
                Method subscribeMethod = eventBusClass.getMethod("subscribe", Class.class, Class.class);

                LuckPermsEventListener listener = new LuckPermsEventListener(plugin, rewardListener);
                subscribeMethod.invoke(eventBus, userUpdateClass, listener);

                enabled = true;
                plugin.getLogger().info("已启用 LuckPerms 实时权限监听");
            }
        } catch (Exception e) {
            plugin.getLogger().fine("LuckPerms API 不可用，将使用轮询检测: " + e.getMessage());
        }

        this.luckPermsEnabled = enabled;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!luckPermsEnabled) {
            return;
        }

        Player player = event.getPlayer();
        plugin.getScheduler().runGlobalDelayed(() ->
            rewardListener.checkOnlinePlayerPermissionGroup(player), 5L);
    }

    private static class LuckPermsEventListener {
        private final ALInvite plugin;
        private final PermissionGroupRewardListener rewardListener;

        public LuckPermsEventListener(ALInvite plugin, PermissionGroupRewardListener rewardListener) {
            this.plugin = plugin;
            this.rewardListener = rewardListener;
        }

        public void onUserUpdate(Object event) {
            try {
                Method getUser = event.getClass().getMethod("getUser");
                Object user = getUser.invoke(event);
                if (user != null) {
                    Method getUniqueId = user.getClass().getMethod("getUniqueId");
                    UUID uuid = (UUID) getUniqueId.invoke(user);
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        plugin.getScheduler().runGlobal(() ->
                            rewardListener.checkOnlinePlayerPermissionGroup(player));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("处理 LuckPerms 事件失败: " + e.getMessage());
            }
        }
    }
}
