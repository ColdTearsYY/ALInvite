package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.utils.VaultEconomyUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 奖励发放唯一入口（command / money / points / item）。
 * 纪律：任何发放动作先经统一调度器切回玩家实体线程再执行，
 * 绝不允许在异步线程操作背包、经济或分发命令。
 */
public class RewardService {

    private final ALInvite plugin;

    public RewardService(ALInvite plugin) {
        this.plugin = plugin;
    }

    /** 兼容旧 Reward 结构（type/value 文本对）。 */
    public void giveRewards(Player player, List<MilestoneManager.Reward> rewards) {
        if (rewards == null) {
            return;
        }
        for (MilestoneManager.Reward reward : rewards) {
            switch (reward.type) {
                case "command" -> giveCommand(player, reward.value.toString());
                case "money" -> giveMoney(player, Double.parseDouble(reward.value.toString()));
                case "points" -> givePoints(player, reward.value.toString());
                case "item" -> giveItem(player, reward.value.toString());
                default -> plugin.getLogger().warning("未知奖励类型: " + reward.type);
            }
        }
    }

    public void giveCommand(Player player, String command) {
        String resolved = command.replace("%player%", player.getName());
        plugin.getScheduler().runAtPlayer(player, () -> {
            try {
                boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
                if (!success) {
                    plugin.getLogger().warning("奖励命令返回失败: " + resolved);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("奖励命令执行失败: " + resolved + " - " + e.getMessage());
            }
        });
    }

    public void giveMoney(Player player, double amount) {
        plugin.getScheduler().runAtPlayer(player, () ->
            VaultEconomyUtils.deposit(plugin, player, amount));
    }

    public void givePoints(Player player, String value) {
        int amount;
        try {
            amount = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("点券奖励数值无效: " + value);
            return;
        }
        givePoints(player, amount);
    }

    public void givePoints(Player player, int amount) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "NONE");
        if ("NONE".equals(pointsType)) {
            return;
        }
        switch (pointsType) {
            case "PLAYERPOINTS" -> plugin.getScheduler().runAtPlayer(player, () -> {
                try {
                    Object api = getPlayerPointsAPI();
                    if (api == null) {
                        return;
                    }
                    Method giveMethod = api.getClass().getMethod("give", UUID_CLASS, int.class);
                    giveMethod.invoke(api, player.getUniqueId(), amount);
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints 发放失败: " + e.getMessage());
                }
            });
            case "CUSTOM" -> {
                String giveCmd = plugin.getConfigManager().getConfig()
                    .getString("economy.points_command.give", "")
                    .replace("%player%", player.getName())
                    .replace("%amount%", String.valueOf(amount));
                giveCommand(player, giveCmd);
            }
            default -> plugin.getLogger().warning("未知的点券类型: " + pointsType + "，请检查 economy.points_type");
        }
    }

    public void giveItem(Player player, String value) {
        String[] parts = value.trim().split(" ");
        if (parts.length < 2) {
            plugin.getLogger().warning("物品奖励格式无效（应为 物品 数量）: " + value);
            return;
        }
        Material material = com.alinvite.utils.ItemUtil.parseMaterial(parts[0], null, null);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("无效的物品奖励: " + value);
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("物品奖励数量无效: " + value);
            return;
        }
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        plugin.getScheduler().runAtPlayer(player, () -> {
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                for (ItemStack left : leftover.values()) {
                    player.getWorld().dropItem(player.getLocation(), left);
                }
            }
        });
    }

    // ─── PlayerPoints 反射（唯一实现，购买扣点与发放共用） ───

    public boolean hasEnoughPoints(Player player, int amount) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "NONE");
        if ("NONE".equals(pointsType)) {
            return true;
        }
        switch (pointsType) {
            case "PLAYERPOINTS" -> {
                try {
                    Object api = getPlayerPointsAPI();
                    if (api == null) {
                        return false;
                    }
                    return getPlayerPoints(api, player) >= amount;
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints 检查失败: " + e.getMessage());
                    return false;
                }
            }
            case "CUSTOM" -> {
                return true;
            }
            default -> {
                plugin.getLogger().warning("未知的点券类型: " + pointsType + "，请检查 economy.points_type");
                return false;
            }
        }
    }

    public void takePoints(Player player, int amount) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "NONE");
        if ("NONE".equals(pointsType)) {
            return;
        }
        switch (pointsType) {
            case "PLAYERPOINTS" -> plugin.getScheduler().runAtPlayer(player, () -> {
                try {
                    Object api = getPlayerPointsAPI();
                    if (api == null) {
                        return;
                    }
                    Method takeMethod = api.getClass().getMethod("take", UUID_CLASS, int.class);
                    takeMethod.invoke(api, player.getUniqueId(), amount);
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints 扣除失败: " + e.getMessage());
                }
            });
            case "CUSTOM" -> {
                String takeCmd = plugin.getConfigManager().getConfig()
                    .getString("economy.points_command.take", "")
                    .replace("%player%", player.getName())
                    .replace("%amount%", String.valueOf(amount));
                giveCommand(player, takeCmd);
            }
            default -> plugin.getLogger().warning("未知的点券类型: " + pointsType);
        }
    }

    public Object getPlayerPointsAPI() {
        try {
            Class<?> ppClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
            Object ppInstance = ppClass.getMethod("getInstance").invoke(null);
            if (ppInstance == null) {
                plugin.getLogger().warning("PlayerPoints.getInstance() 返回 null！");
                return null;
            }
            Object api = ppClass.getMethod("getAPI").invoke(ppInstance);
            if (api == null) {
                plugin.getLogger().warning("PlayerPoints.getAPI() 返回 null！");
            }
            return api;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("找不到 PlayerPoints 类！请确保 PlayerPoints 插件已安装。");
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("获取 PlayerPoints API 失败: " + e.getMessage());
            return null;
        }
    }

    private int getPlayerPoints(Object api, Player player) {
        try {
            Method lookMethod = api.getClass().getMethod("look", UUID_CLASS);
            Object result = lookMethod.invoke(api, player.getUniqueId());
            if (result instanceof Number number) {
                return number.intValue();
            }
            return 0;
        } catch (Exception e) {
            plugin.getLogger().warning("获取玩家点券失败: " + e.getMessage());
            return 0;
        }
    }

    private static final Class<?> UUID_CLASS = UUID.class;
}
