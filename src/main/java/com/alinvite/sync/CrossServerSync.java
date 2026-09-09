package com.alinvite.sync;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 跨服同步：基于 Redis Pub/Sub 的轻量消息协议。
 *
 * 消息格式（\u001F 分隔）：
 *   ANNOUNCE \u001F 来源服务器ID \u001F 来源别称 \u001F 公告文本
 *   INVALIDATE \u001F 来源服务器ID \u001F 玩家UUID \u001F 缓存类型列表(逗号分隔)
 *
 * 收到其它服务器的消息时在本服执行；自己发的消息直接忽略（本服已就地处理）。
 */
public class CrossServerSync implements DatabaseManager.DataChangeListener {

    private static final String SEP = "\u001F";

    private final ALInvite plugin;

    public CrossServerSync(ALInvite plugin) {
        this.plugin = plugin;
    }

    /** 发布里程碑公告到集群（本服的本地广播由调用方完成）。 */
    public void sendAnnouncement(String message) {
        if (plugin.getRedisManager() == null || !plugin.getRedisManager().isAvailable()) {
            return;
        }
        plugin.getRedisManager().publish(String.join(SEP,
            "ANNOUNCE", plugin.getConfigManager().getServerId(),
            plugin.getConfigManager().getServerAlias(), stripSep(message)));
    }

    /** 通知集群内其它服务器失效某玩家的指定缓存（kinds 如 stats / gift / contribution / rebate / inviter / invitecode）。 */
    public void sendInvalidate(UUID playerId, String... kinds) {
        if (kinds == null || kinds.length == 0
            || plugin.getRedisManager() == null || !plugin.getRedisManager().isAvailable()) {
            return;
        }
        plugin.getRedisManager().publish(String.join(SEP,
            "INVALIDATE", plugin.getConfigManager().getServerId(),
            playerId.toString(), String.join(",", kinds)));
    }

    /**
     * 数据变更回调（DatabaseManager 所有写方法的单一漏斗，ALFriends 式写穿透）：
     * 本机缓存立即失效（零延迟），同时广播给集群内其它服务器。
     */
    @Override
    public void onDataChanged(UUID playerId, String kind) {
        invalidate(playerId, kind);
        if (plugin.getRedisManager() != null && plugin.getRedisManager().isAvailable()) {
            plugin.getRedisManager().publish(String.join(SEP,
                "INVALIDATE", plugin.getConfigManager().getServerId(),
                playerId.toString(), kind));
        }
    }

    /** 订阅线程回调：只做分发，所有 Bukkit 操作切回全局线程。 */
    public void handleIncoming(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split(SEP, -1);
        if (parts.length < 2) {
            return;
        }
        String type = parts[0];
        String fromServer = parts[1];
        if (fromServer.equals(plugin.getConfigManager().getServerId())) {
            return; // 自己发的消息
        }

        switch (type) {
            case "ANNOUNCE" -> {
                if (parts.length < 4) {
                    return;
                }
                String alias = parts[2];
                String message = parts[3];
                plugin.getScheduler().runGlobal(() -> {
                    String line = ConfigManager.colorize("&8[&e" + alias + "&8]&r " + message);
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendMessage(line);
                    }
                });
            }
            case "INVALIDATE" -> {
                if (parts.length < 4) {
                    return;
                }
                try {
                    UUID playerId = UUID.fromString(parts[2]);
                    for (String kind : parts[3].split(",")) {
                        invalidate(playerId, kind.trim());
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            default -> {
            }
        }
    }

    private void invalidate(UUID playerId, String kind) {
        switch (kind) {
            case "stats" -> plugin.getCacheManager().invalidateStats(playerId);
            case "contribution" -> plugin.getCacheManager().invalidateContribution(playerId);
            case "rebate" -> plugin.getCacheManager().invalidateTotalRebate(playerId);
            case "gift" -> {
                plugin.getCacheManager().invalidateGiftId(playerId);
                plugin.getCacheManager().invalidateGiftPurchaseTime(playerId);
            }
            case "inviter" -> plugin.getCacheManager().invalidateInviter(playerId);
            case "invitecode" -> plugin.getCacheManager().invalidateInviteCode(playerId);
            case "claimed", "pending", "unclaimed" -> {
                // 这些数据没有本地缓存，无需处理；保留 kind 以便扩展
            }
            case "reset" -> {
                plugin.getCacheManager().invalidateStats(playerId);
                plugin.getCacheManager().invalidateContribution(playerId);
                plugin.getCacheManager().invalidateTotalRebate(playerId);
                plugin.getCacheManager().invalidateGiftId(playerId);
                plugin.getCacheManager().invalidateInviter(playerId);
                plugin.getCacheManager().invalidateInviteCode(playerId);
                plugin.getCacheManager().invalidateGiftPurchaseTime(playerId);
            }
            default -> {
            }
        }
    }

    private static String stripSep(String text) {
        return text == null ? "" : text.replace(SEP, " ");
    }
}
