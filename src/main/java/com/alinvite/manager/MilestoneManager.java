package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 里程碑管理：配置加载、达标检测、自动/待领取、奖励发放与领取。
 * 奖励发放统一走 {@link RewardService}（内部切实体线程）。
 */
public class MilestoneManager {

    private final ALInvite plugin;
    private final Map<Integer, Milestone> milestones = new TreeMap<>();
    private final Set<String> claimInProgress = ConcurrentHashMap.newKeySet();

    public MilestoneManager(ALInvite plugin) {
        this.plugin = plugin;
        loadMilestones();
    }

    private RewardService rewards() {
        return plugin.getRewardService();
    }

    private ALInviteScheduler scheduler() {
        return plugin.getScheduler();
    }

    /** 发放前触发可取消的 MilestoneClaimEvent；被取消则跳过奖励（领取资格仍保留）。 */
    private void grantClaimRewards(Player player, Milestone milestone, int required) {
        scheduler().runGlobal(() -> {
            com.alinvite.api.event.MilestoneClaimEvent event =
                new com.alinvite.api.event.MilestoneClaimEvent(player, required, milestone);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                rewards().giveRewards(player, milestone.rewards);
            }
        });
    }

    public void loadMilestones() {
        milestones.clear();
        ConfigurationSection milestonesConfig = plugin.getConfigManager().getConfig().getConfigurationSection("milestones");
        if (milestonesConfig == null) {
            return;
        }

        for (String key : milestonesConfig.getKeys(false)) {
            if (key.equals("auto_claim")) {
                continue;
            }
            try {
                int required = Integer.parseInt(key);
                String name = milestonesConfig.getString(key + ".name", "里程碑 " + required);
                List<Reward> rewards = loadRewards(key);
                List<String> lore = milestonesConfig.getStringList(key + ".lore");
                milestones.put(required, new Milestone(name, rewards, lore));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("无效的里程碑配置: " + key);
            }
        }
    }

    private List<Reward> loadRewards(String milestoneKey) {
        List<Reward> rewards = new ArrayList<>();
        List<Map<?, ?>> rewardList = plugin.getConfigManager().getConfig()
            .getMapList("milestones." + milestoneKey + ".rewards");

        for (Map<?, ?> rewardMap : rewardList) {
            String type = (String) rewardMap.get("type");
            Object value = rewardMap.get("value");
            if (type != null && value != null) {
                rewards.add(new Reward(type, value));
            }
        }
        return rewards;
    }

    /** 邀请数变化后调用（可在任意线程）。 */
    public void checkMilestones(UUID playerUuid, int totalInvites) {
        Player player = Bukkit.getPlayer(playerUuid);
        boolean autoClaim = plugin.getConfigManager().getConfig().getBoolean("milestones.auto_claim", true);
        boolean sendOnlyOnce = plugin.getConfigManager().getConfig().getBoolean("announcements.send_only_once", true);

        plugin.getDatabaseManager().getPlayerData(playerUuid).thenAccept(playerData -> {
            if (playerData != null && !playerData.milestoneEnabled) {
                return;
            }

            plugin.getDatabaseManager().getClaimedMilestones(playerUuid).thenAccept(claimedJson -> {
                Set<String> claimed = parseStringSet(claimedJson);

                for (Map.Entry<Integer, Milestone> entry : milestones.entrySet()) {
                    int required = entry.getKey();
                    Milestone milestone = entry.getValue();

                    if (totalInvites >= required && !claimed.contains(String.valueOf(required))) {
                        if (autoClaim) {
                            if (player != null) {
                                // 抢占成功才发放，防止跨服/并发二次发放
                                plugin.getDatabaseManager().claimMilestone(playerUuid, String.valueOf(required))
                                    .thenAccept(claimWon -> {
                                        if (Boolean.TRUE.equals(claimWon)) {
                                            grantClaimRewards(player, milestone, required);
                                        }
                                    });
                            } else {
                                plugin.getDatabaseManager().addPendingMilestone(playerUuid, String.valueOf(required));
                            }
                        } else if (player != null) {
                            scheduler().runAtPlayer(player, () ->
                                player.sendMessage(plugin.getConfigManager().getMessage("milestone.unlocked", player)
                                    .replace("{name}", milestone.name)));
                        }

                        if (plugin.getConfigManager().getConfig().getBoolean("announcements.enabled", true) && player != null) {
                            if (sendOnlyOnce) {
                                plugin.getDatabaseManager().getAnnouncedMilestones(playerUuid).thenAccept(announced -> {
                                    if (!parseStringSet(announced).contains(String.valueOf(required))) {
                                        sendAnnouncement(player, milestone, totalInvites);
                                        plugin.getDatabaseManager().addAnnouncedMilestone(playerUuid, String.valueOf(required));
                                    }
                                });
                            } else {
                                sendAnnouncement(player, milestone, totalInvites);
                            }
                        }
                    }
                }
            });
        });
    }

    /** 玩家上线补发待领取里程碑（可在任意线程，发放动作由 RewardService 切线程）。 */
    public java.util.concurrent.CompletableFuture<Void> checkPendingMilestones(Player player) {
        return plugin.getDatabaseManager().getPendingMilestones(player.getUniqueId())
            .thenCompose(pending -> plugin.getDatabaseManager().getClaimedMilestones(player.getUniqueId())
                .thenAccept(claimedJson -> {
                    Set<String> claimed = parseStringSet(claimedJson);
                    for (String milestoneKey : pending) {
                        if (claimed.contains(milestoneKey)) {
                            plugin.getDatabaseManager().removePendingMilestone(player.getUniqueId(), milestoneKey);
                            continue;
                        }
                        try {
                            Milestone milestone = milestones.get(Integer.parseInt(milestoneKey));
                            if (milestone != null) {
                                plugin.getDatabaseManager().claimMilestone(player.getUniqueId(), milestoneKey)
                                    .thenAccept(claimWon -> {
                                        if (Boolean.TRUE.equals(claimWon)) {
                                            grantClaimRewards(player, milestone, Integer.parseInt(milestoneKey));
                                        }
                                        plugin.getDatabaseManager().removePendingMilestone(player.getUniqueId(), milestoneKey);
                                    });
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }));
    }

    /**
     * GUI 领取里程碑（原 MenuListener.handleClaimMilestone）。
     * 全链无 join；写回消息经实体线程。
     */
    public void claimFromMenu(Player player, int required) {
        Milestone milestone = milestones.get(required);
        if (milestone == null) {
            plugin.getLogger().warning("Attempted to claim unknown milestone: " + required);
            return;
        }

        String milestoneId = String.valueOf(required);
        plugin.getDatabaseManager().getClaimedMilestones(player.getUniqueId())
            .thenCompose(claimedJson -> plugin.getDatabaseManager().getPlayerData(player.getUniqueId())
                .thenCompose(data -> {
                    int total = data != null ? data.totalInvites : 0;
                    Set<String> claimed = parseStringSet(claimedJson);

                    if (claimed.contains(milestoneId)) {
                        scheduler().runAtPlayer(player, () ->
                            player.sendMessage(plugin.getConfigManager().getMessage("milestone.already_claimed", player)));
                        return CompletableFutureHolder.completedNull();
                    }
                    if (total < required) {
                        scheduler().runAtPlayer(player, () ->
                            player.sendMessage(plugin.getConfigManager().getMessage("milestone.not_unlocked", player)
                                .replace("{name}", milestone.name)
                                .replace("{remaining}", String.valueOf(required - total))));
                        return CompletableFutureHolder.completedNull();
                    }

                    String claimKey = player.getUniqueId() + ":" + required;
                    if (!claimInProgress.add(claimKey)) {
                        return CompletableFutureHolder.completedNull();
                    }

                    return plugin.getDatabaseManager().claimMilestone(player.getUniqueId(), milestoneId)
                        .whenComplete((v, throwable) -> {
                            claimInProgress.remove(claimKey);
                            if (throwable != null) {
                                plugin.getLogger().severe("Failed to save milestone claim: " + throwable.getMessage());
                            }
                        })
                        .thenAccept(claimWon -> scheduler().runAtPlayer(player, () -> {
                            if (!Boolean.TRUE.equals(claimWon)) {
                                // 被本机并发请求或其它服务器抢先领取
                                player.sendMessage(plugin.getConfigManager().getMessage("milestone.already_claimed", player));
                                return;
                            }
                            grantClaimRewards(player, milestone, required);
                            player.sendMessage(plugin.getConfigManager().getMessage("milestone.claim_success", player)
                                .replace("{name}", milestone.name));

                            if (plugin.getConfigManager().getConfig().getBoolean("announcements.enabled", true)) {
                                boolean sendOnlyOnce = plugin.getConfigManager().getConfig()
                                    .getBoolean("announcements.send_only_once", true);
                                if (sendOnlyOnce) {
                                    plugin.getDatabaseManager().getAnnouncedMilestones(player.getUniqueId())
                                        .thenAccept(announced -> {
                                            if (!parseStringSet(announced).contains(milestoneId)) {
                                                sendAnnouncement(player, milestone, total);
                                                plugin.getDatabaseManager().addAnnouncedMilestone(player.getUniqueId(), milestoneId);
                                            }
                                        });
                                } else {
                                    sendAnnouncement(player, milestone, total);
                                }
                            }

                            player.closeInventory();
                            scheduler().runAtPlayerDelayed(player, () ->
                                plugin.getMenuManager().openVeteranMenu(player), 1L);
                        }));
                }));
    }

    public void sendAnnouncement(Player player, Milestone milestone, int totalInvites) {
        String messageTemplate = plugin.getConfigManager().getConfig()
            .getString("announcements.messages." + totalInvites,
                plugin.getConfigManager().getConfig()
                    .getString("announcements.messages.default",
                        "&6[邀请系统] &e{player} &a累计邀请人数达到 &6{total} &a人，获得里程碑 &6{milestone_name}&a！"));

        String template = messageTemplate.replace("{player}", player.getName())
            .replace("{total}", String.valueOf(totalInvites))
            .replace("{milestone_name}", milestone.name);

        String mode = plugin.getConfigManager().getConfig().getString("announcements.mode", "BROADCAST");
        switch (mode.toUpperCase()) {
            case "WORLD" -> scheduler().runAtPlayer(player, () -> {
                String personalized = ConfigManager.colorize(template, player);
                for (Player onlinePlayer : player.getWorld().getPlayers()) {
                    scheduler().runAtPlayer(onlinePlayer, () ->
                        onlinePlayer.sendMessage(ConfigManager.colorize(personalized, onlinePlayer)));
                }
            });
            case "CONSOLE" -> plugin.getLogger().info(ConfigManager.colorize(template));
            default -> scheduler().runGlobal(() -> {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    scheduler().runAtPlayer(onlinePlayer, () ->
                        onlinePlayer.sendMessage(ConfigManager.colorize(template, onlinePlayer)));
                }
                // 跨服广播：受 announcements.cross_server_sync 开关控制，同步到集群内其它服务器
                if (plugin.getSync() != null
                        && plugin.getConfigManager().getConfig().getBoolean("announcements.cross_server_sync", true)) {
                    plugin.getSync().sendAnnouncement(template);
                }
            });
        }
    }

    public Map<Integer, Milestone> getMilestones() {
        return milestones;
    }

    public Milestone getMilestone(int required) {
        return milestones.get(required);
    }

    private Set<String> parseStringSet(String json) {
        Set<String> result = new java.util.HashSet<>();
        if (json == null || json.trim().isEmpty() || json.equals("[]")) {
            return result;
        }
        try {
            for (String part : json.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static class Milestone {
        public final String name;
        public final List<Reward> rewards;
        public final List<String> lore;

        public Milestone(String name, List<Reward> rewards, List<String> lore) {
            this.name = name;
            this.rewards = rewards;
            this.lore = lore;
        }
    }

    public static class Reward {
        public final String type;
        public final Object value;

        public Reward(String type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    /** 内部小工具：CompletableFuture&lt;Void&gt; 的已完成占位。 */
    private static final class CompletableFutureHolder {
        private static final java.util.concurrent.CompletableFuture<Void> DONE =
            java.util.concurrent.CompletableFuture.completedFuture(null);

        static java.util.concurrent.CompletableFuture<Void> completedNull() {
            return DONE;
        }
    }
}
