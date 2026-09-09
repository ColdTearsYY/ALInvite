package com.alinvite.listeners;

import com.alinvite.ALInvite;
import com.alinvite.utils.VaultEconomyUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.*;

public class PermissionGroupRewardListener implements Listener {

    private final ALInvite plugin;
    private final Map<UUID, String> lastKnownGroup = new HashMap<>();
    private final Map<UUID, List<String>> pendingOfflineMessages = new HashMap<>();
    private final Map<UUID, List<double[]>> pendingOfflineMoney = new HashMap<>();

    public PermissionGroupRewardListener(ALInvite plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().getConfig().getBoolean("permission_group_rewards.enabled", false)) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        plugin.getDatabaseManager().getPlayerData(playerUuid).thenAccept(data -> {
            if (data != null && data.lastPermissionGroup != null) {
                lastKnownGroup.put(playerUuid, data.lastPermissionGroup);
            }
        });

        List<double[]> moneyList = pendingOfflineMoney.remove(playerUuid);
        if (moneyList != null && !moneyList.isEmpty()) {
            String moneyCommand = plugin.getConfigManager().getConfig()
                .getString("economy.money_command.give", "");
            if (!moneyCommand.isEmpty()) {
                for (double[] info : moneyList) {
                    double amount = info[0];
                    String cmd = moneyCommand
                        .replace("%player%", player.getName())
                        .replace("%amount%", String.valueOf((int) amount));
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }
        }

        List<String> messages = pendingOfflineMessages.remove(playerUuid);
        if (messages != null && !messages.isEmpty()) {
            for (String msg : messages) {
                player.sendMessage(msg);
            }
        }

        plugin.getScheduler().runGlobalDelayed(() ->
            checkGroupUpgrade(player, playerUuid), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfigManager().getConfig().getBoolean("permission_group_rewards.enabled", false)) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        plugin.getScheduler().runGlobalDelayed(() ->
            checkGroupUpgrade(player, playerUuid), 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastKnownGroup.remove(uuid);
    }

    public void manualCheck(Player player) {
        if (!plugin.getConfigManager().getConfig().getBoolean("permission_group_rewards.enabled", false)) {
            return;
        }
        checkGroupUpgrade(player, player.getUniqueId(), true);
    }

    public void checkOnlinePlayerPermissionGroup(Player player) {
        if (!plugin.getConfigManager().getConfig().getBoolean("permission_group_rewards.enabled", false)) {
            return;
        }
        checkGroupUpgrade(player, player.getUniqueId());
    }

    private String getCheckMode() {
        return plugin.getConfigManager().getConfig()
            .getString("permission_group_rewards.check_mode", "PERMISSION");
    }

    private void checkGroupUpgrade(Player player, UUID playerUuid) {
        checkGroupUpgrade(player, playerUuid, false);
    }

    private void checkGroupUpgrade(Player player, UUID playerUuid, boolean forceCheck) {
        plugin.getDatabaseManager().getInviter(playerUuid).thenAccept(inviterUuid -> {
            if (inviterUuid == null) {
                return;
            }

            String checkMode = getCheckMode();
            String currentGroup = getPlayerGroup(player, checkMode);
            if (currentGroup == null) {
                return;
            }

            String rewardPath = "permission_group_rewards.rewards." + currentGroup;
            if (!plugin.getConfigManager().getConfig().contains(rewardPath)) {
                return;
            }

            plugin.getDatabaseManager().getClaimedPermissionGroups(inviterUuid, playerUuid).thenAccept(claimedGroups -> {
                if (claimedGroups.contains(currentGroup) && !forceCheck) {
                    plugin.getDatabaseManager().updatePermissionGroup(playerUuid, currentGroup);
                    lastKnownGroup.put(playerUuid, currentGroup);
                    return;
                }

                double money = plugin.getConfigManager().getConfig().getDouble(rewardPath + ".money", 0.0);
                int points = plugin.getConfigManager().getConfig().getInt(rewardPath + ".points", 0);

                if (money > 0 || points > 0) {
                    giveRewardIfNeeded(inviterUuid, money, points, player.getName(), currentGroup);
                }

                plugin.getDatabaseManager().addClaimedPermissionGroup(inviterUuid, playerUuid, currentGroup)
                    .thenCompose(v -> plugin.getDatabaseManager().updatePermissionGroup(playerUuid, currentGroup))
                    .thenRun(() -> lastKnownGroup.put(playerUuid, currentGroup));
            });
        });
    }

    private void giveRewardIfNeeded(UUID inviterUuid, double money, int points, String newPlayerName, String group) {
        Player inviter = Bukkit.getPlayer(inviterUuid);

        if (money > 0) {
            boolean vaultAvailable = VaultEconomyUtils.isAvailable(plugin);
            if (inviter != null && vaultAvailable) {
                plugin.getScheduler().runAtPlayer(inviter, () -> {
                    if (VaultEconomyUtils.deposit(plugin, inviter, money)) {
                        String msg = plugin.getConfigManager().getMessage("permission_group_reward.money")
                            .replace("{player}", newPlayerName)
                            .replace("{group}", group)
                            .replace("{money}", String.valueOf(money));
                        inviter.sendMessage(msg);
                    }
                });
            } else if (inviter == null && vaultAvailable) {
                giveOfflineMoney(inviterUuid, money, newPlayerName, group);
            }

            if (!vaultAvailable) {
                String moneyCommand = plugin.getConfigManager().getConfig()
                    .getString("economy.money_command.give", "");
                if (!moneyCommand.isEmpty()) {
                    String targetName = inviter != null ? inviter.getName() : inviterUuid.toString();
                    String cmd = moneyCommand
                        .replace("%player%", targetName)
                        .replace("%amount%", String.valueOf((int) money));
                    if (inviter != null) {
                        plugin.getScheduler().runAtPlayer(inviter, () -> {
                            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);
                            String msg = plugin.getConfigManager().getMessage("permission_group_reward.money")
                                .replace("{player}", newPlayerName)
                                .replace("{group}", group)
                                .replace("{money}", String.valueOf(money));
                            inviter.sendMessage(msg);
                        });
                    } else {
                        plugin.getScheduler().runGlobal(() ->
                            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd));
                    }
                }
            }
        }

        if (points > 0) {
            if (inviter != null) {
                plugin.getScheduler().runAtPlayer(inviter, () ->
                    givePointsToPlayer(inviterUuid, points, newPlayerName, group, true));
            } else {
                plugin.getScheduler().runGlobal(() ->
                    givePointsToPlayer(inviterUuid, points, newPlayerName, group, false));
            }
        }
    }

    private void giveOfflineMoney(UUID playerUuid, double amount, String newPlayerName, String group) {
        String msg = plugin.getConfigManager().getMessage("permission_group_reward.offline_money")
            .replace("{player}", newPlayerName)
            .replace("{group}", group)
            .replace("{money}", String.valueOf(amount));
        addPendingOfflineMessage(playerUuid, msg);

        pendingOfflineMoney.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(new double[]{amount});
    }

    private void addPendingOfflineMessage(UUID playerUuid, String message) {
        pendingOfflineMessages.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(message);
    }

    private void givePointsToPlayer(UUID playerUuid, int amount, String newPlayerName, String group, boolean isOnline) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "VAULT");

        Player player = Bukkit.getPlayer(playerUuid);

        switch (pointsType) {
            case "VAULT" -> {
                String moneyCommand = plugin.getConfigManager().getConfig()
                    .getString("economy.money_command.give", "")
                    .replace("%player%", player != null ? player.getName() : playerUuid.toString())
                    .replace("%amount%", String.valueOf(amount));
                if (!moneyCommand.isEmpty()) {
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), moneyCommand);
                }
            }
            case "CUSTOM" -> {
                String giveCmd = plugin.getConfigManager().getConfig()
                    .getString("economy.points_command.give", "")
                    .replace("%player%", player != null ? player.getName() : playerUuid.toString())
                    .replace("%amount%", String.valueOf(amount));
                if (!giveCmd.isEmpty()) {
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), giveCmd);
                }
            }
            case "PLAYERPOINTS" -> {
                try {
                    Class<?> ppClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
                    Object ppInstance = ppClass.getMethod("getInstance").invoke(null);
                    if (ppInstance == null) return;
                    Object api = ppClass.getMethod("getAPI").invoke(ppInstance);
                    if (api == null) return;
                    java.lang.reflect.Method giveMethod = api.getClass().getMethod("give", UUID.class, int.class);
                    giveMethod.invoke(api, playerUuid, amount);
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints 发放失败: " + e.getMessage());
                }
            }
        }

        if (isOnline && player != null) {
            String msg = plugin.getConfigManager().getMessage("permission_group_reward.points")
                .replace("{player}", newPlayerName)
                .replace("{group}", group)
                .replace("{points}", String.valueOf(amount));
            player.sendMessage(msg);
        } else if (!isOnline) {
            String msg = plugin.getConfigManager().getMessage("permission_group_reward.offline_points")
                .replace("{player}", newPlayerName)
                .replace("{group}", group)
                .replace("{points}", String.valueOf(amount));
            addPendingOfflineMessage(playerUuid, msg);
        }
    }

    private String getPlayerGroup(Player player, String checkMode) {
        if ("PERMISSION".equals(checkMode)) {
            String prefix = plugin.getConfigManager().getConfig()
                .getString("permission_group_rewards.permission_prefix", "alinvite");
            var rewards = plugin.getConfigManager().getConfig()
                .getConfigurationSection("permission_group_rewards.rewards");
            if (rewards == null) return null;

            String highestGroup = null;
            int highestWeight = -1;

            for (String group : rewards.getKeys(false)) {
                if (player.hasPermission(prefix + "." + group)) {
                    int weight = rewards.getInt(group + ".weight", 0);
                    if (weight > highestWeight) {
                        highestWeight = weight;
                        highestGroup = group;
                    }
                }
            }
            return highestGroup;
        }
        return null;
    }
}
