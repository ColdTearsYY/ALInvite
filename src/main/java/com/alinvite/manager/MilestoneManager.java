package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.utils.SchedulerUtils;
import com.alinvite.utils.VaultEconomyUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MilestoneManager {

    private final ALInvite plugin;
    private final Map<Integer, Milestone> milestones;

    public MilestoneManager(ALInvite plugin) {
        this.plugin = plugin;
        this.milestones = new TreeMap<>();
        loadMilestones();
    }

    private void loadMilestones() {
        ConfigurationSection milestonesConfig = plugin.getConfigManager().getConfig().getConfigurationSection("milestones");
        if (milestonesConfig == null) return;

        for (String key : milestonesConfig.getKeys(false)) {
            if (key.equals("auto_claim")) continue;
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

    public void checkMilestones(UUID playerUuid, int totalInvites) {
        Player player = Bukkit.getPlayer(playerUuid);
        boolean autoClaim = plugin.getConfigManager().getConfig().getBoolean("milestones.auto_claim", true);
        boolean sendOnlyOnce = plugin.getConfigManager().getConfig().getBoolean("announcements.send_only_once", true);

        // 检查玩家是否启用了里程碑功能
        plugin.getDatabaseManager().getPlayerData(playerUuid).thenAccept(playerData -> {
            if (playerData != null && !playerData.milestoneEnabled) {
                return;
            }

            plugin.getDatabaseManager().getClaimedMilestones(playerUuid).thenAccept(claimedJson -> {
                Set<String> claimed = parseJsonArray(claimedJson);

                for (Map.Entry<Integer, Milestone> entry : milestones.entrySet()) {
                    int required = entry.getKey();
                    Milestone milestone = entry.getValue();

                    if (totalInvites >= required && !claimed.contains(String.valueOf(required))) {
                        if (autoClaim) {
                            if (player != null) {
                                giveRewards(player, milestone);
                                plugin.getDatabaseManager().claimMilestone(playerUuid, String.valueOf(required));
                            } else {
                                plugin.getDatabaseManager().addPendingMilestone(playerUuid, String.valueOf(required));
                            }
                        } else {
                            if (player != null) {
                                String msg = plugin.getConfigManager().getMessage("milestone.unlocked", player)
                                    .replace("{name}", milestone.name);
                                player.sendMessage(msg);
                            }
                        }

                        if (plugin.getConfigManager().getConfig().getBoolean("announcements.enabled", true)) {
                            if (player != null) {
                                if (sendOnlyOnce) {
                                    plugin.getDatabaseManager().getAnnouncedMilestones(playerUuid).thenAccept(announced -> {
                                        Set<String> announcedSet = parseJsonArray(announced);
                                        if (!announcedSet.contains(String.valueOf(required))) {
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
                }
            });
        });
    }

    public void checkPendingMilestones(Player player) {
        plugin.getDatabaseManager().getPendingMilestones(player.getUniqueId()).thenCompose(pending -> {
            return plugin.getDatabaseManager().getClaimedMilestones(player.getUniqueId()).thenAccept(claimedJson -> {
                Set<String> claimed = parseJsonArray(claimedJson);

                for (String milestoneKey : pending) {
                    if (claimed.contains(milestoneKey)) {
                        plugin.getDatabaseManager().removePendingMilestone(player.getUniqueId(), milestoneKey);
                        continue;
                    }

                    Milestone milestone = milestones.get(Integer.parseInt(milestoneKey));
                    if (milestone != null) {
                        giveRewards(player, milestone);
                        plugin.getDatabaseManager().claimMilestone(player.getUniqueId(), milestoneKey);
                        plugin.getDatabaseManager().removePendingMilestone(player.getUniqueId(), milestoneKey);
                    }
                }
            });
        });
    }

    private Set<String> parseJsonArray(String json) {
        if (json == null || json.equals("[]")) {
            return new HashSet<>();
        }
        String trimmed = json.substring(1, json.length() - 1);
        if (trimmed.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> result = new HashSet<>();
        for (String s : trimmed.split(",")) {
            result.add(s.replace("\"", "").trim());
        }
        return result;
    }

    public void giveRewards(Player player, Milestone milestone) {
        for (Reward reward : milestone.rewards) {
            switch (reward.type) {
                case "command" -> {
                    String command = reward.value.toString()
                        .replace("%player%", player.getName());
                    SchedulerUtils.runTask(plugin, () ->
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command));
                }
                case "money" -> {
                    VaultEconomyUtils.deposit(plugin, player, Double.parseDouble(reward.value.toString()));
                }
                case "points" -> {
                    givePoints(player, reward.value);
                }
                case "item" -> {
                    giveItem(player, reward.value);
                }
            }
        }

        String message = plugin.getConfigManager().getMessage("milestone.reached", player)
            .replace("{name}", milestone.name);
        player.sendMessage(message);
    }

    private void giveItem(Player player, Object value) {
        String[] parts = value.toString().split(" ");
        if (parts.length >= 2) {
            try {
                Material mat = Material.valueOf(parts[0]);
                int amount = Integer.parseInt(parts[1]);
                ItemStack item = new ItemStack(mat, amount);
                player.getInventory().addItem(item);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("礼包物品创建失败: " + value + " - " + e.getMessage());
            }
        }
    }

    private void givePoints(Player player, Object value) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "NONE");
        if ("NONE".equals(pointsType)) return;

        int amount = Integer.parseInt(value.toString());

        switch (pointsType) {
            case "PLAYERPOINTS" -> {
                SchedulerUtils.runTask(plugin, () -> {
                    try {
                        Class<?> ppClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
                        Object ppInstance = ppClass.getMethod("getInstance").invoke(null);
                        if (ppInstance == null) return;
                        Object api = ppClass.getMethod("getAPI").invoke(ppInstance);
                        if (api == null) return;
                        Method giveMethod = api.getClass().getMethod("give", UUID.class, int.class);
                        giveMethod.invoke(api, player.getUniqueId(), amount);
                    } catch (Exception e) {
                        plugin.getLogger().warning("PlayerPoints 发放失败: " + e.getMessage());
                    }
                });
            }
            case "CUSTOM" -> {
                String giveCmd = plugin.getConfigManager().getConfig()
                    .getString("economy.points_command.give", "")
                    .replace("%player%", player.getName())
                    .replace("%amount%", String.valueOf(amount));
                SchedulerUtils.runTask(plugin, () ->
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), giveCmd));
            }
        }
    }

    public void sendAnnouncement(Player player, Milestone milestone, int totalInvites) {
        String messageTemplate = plugin.getConfigManager().getConfig()
            .getString("announcements.messages." + totalInvites,
                plugin.getConfigManager().getConfig()
                    .getString("announcements.messages.default",
                        "&6[邀请系统] &e{player} &a累计邀请人数达到 &6{total} &a人，获得里程碑 &6{milestone_name}&a！"));

        messageTemplate = messageTemplate.replace("{player}", player.getName())
            .replace("{total}", String.valueOf(totalInvites))
            .replace("{milestone_name}", milestone.name);

        String mode = plugin.getConfigManager().getConfig().getString("announcements.mode", "BROADCAST");
        switch (mode.toUpperCase()) {
            case "WORLD" -> {
                for (Player onlinePlayer : player.getWorld().getPlayers()) {
                    // 为每个玩家单独解析 PAPI 占位符
                    String personalizedMsg = ConfigManager.colorize(messageTemplate, onlinePlayer);
                    onlinePlayer.sendMessage(personalizedMsg);
                }
            }
            case "CONSOLE" -> {
                String msg = ConfigManager.colorize(messageTemplate);
                plugin.getLogger().info(msg);
            }
            case "BROADCAST" -> {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    // 为每个玩家单独解析 PAPI 占位符
                    String personalizedMsg = ConfigManager.colorize(messageTemplate, onlinePlayer);
                    onlinePlayer.sendMessage(personalizedMsg);
                }
            }
        }
    }

    public Map<Integer, Milestone> getMilestones() {
        return milestones;
    }

    public Milestone getMilestone(int required) {
        return milestones.get(required);
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
}
