package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动老玩家管理器
 * 通过 PlaceholderAPI 变量检测玩家在线时间，
 * 达到配置的小时数后自动授予老玩家 (veteran) 权限
 */
public class AutoVeteranManager {

    private final ALInvite plugin;
    private final Set<UUID> alreadyGranted = ConcurrentHashMap.newKeySet();
    private int checkTaskId = -1;
    private boolean enabled;
    private String playtimePlaceholder;
    private double requiredHours;   // 转换后的需求小时数
    private long checkInterval;
    private String grantCommand;
    private String valueUnit; // hours, minutes, seconds, ticks

    public AutoVeteranManager(ALInvite plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    /**
     * 加载/重载配置
     */
    public void loadConfig() {
        this.enabled = plugin.getConfigManager().getConfig()
                .getBoolean("auto_veteran.enabled", false);
        this.playtimePlaceholder = plugin.getConfigManager().getConfig()
                .getString("auto_veteran.playtime_placeholder", "%playtime_time_amount%");
        // 解析 playtime 配置（支持 "10h" / "30s" / "5m" 等格式）
        String playtimeRaw = plugin.getConfigManager().getConfig()
                .getString("auto_veteran.playtime", "10h");
        this.requiredHours = parsePlaytime(playtimeRaw);
        this.checkInterval = plugin.getConfigManager().getConfig()
                .getLong("auto_veteran.check_interval", 300);
        this.grantCommand = plugin.getConfigManager().getConfig()
                .getString("auto_veteran.grant_command", "lp user {player} permission set alinvite.veteran true");
        this.valueUnit = plugin.getConfigManager().getConfig()
                .getString("auto_veteran.value_unit", "hours").toLowerCase();

        if (enabled) {
            plugin.getLogger().info("自动老玩家系统已启用");
            plugin.getLogger().info("  在线时间变量: " + playtimePlaceholder);
            plugin.getLogger().info("  需求时长: " + String.format("%.2f", requiredHours) + " 小时");
            plugin.getLogger().info("  检查间隔: " + checkInterval + " 秒");
            plugin.getLogger().info("  数值单位: " + valueUnit);
            alreadyGranted.clear();
            startScheduler();
        } else {
            plugin.getLogger().info("自动老玩家系统已禁用");
        }
    }

    /**
     * 解析 playtime 配置字符串，支持自动单位识别
     * 例如: "30s"=30秒, "5m"=5分钟, "10h"=10小时, "1.5h"=1.5小时
     * @return 转换后的小时数
     */
    private double parsePlaytime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 10.0; // 默认 10 小时
        }
        String trimmed = raw.trim().toLowerCase();

        // 尝试提取数字和单位后缀
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(s|m|h)?");
        java.util.regex.Matcher m = p.matcher(trimmed);
        if (!m.matches()) {
            // 纯数字，默认当作小时
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                return 10.0;
            }
        }

        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2);

        if (unit == null) {
            return value; // 无单位，默认小时
        }

        return switch (unit) {
            case "s" -> value / 3600.0;   // 秒 → 小时
            case "m" -> value / 60.0;     // 分钟 → 小时
            default  -> value;            // h 或未知 → 小时
        };
    }

    /**
     * 启动定时检查（句柄登记，reload 前先 shutdown，杜绝旧定时器泄漏）
     */
    private void startScheduler() {
        shutdown();
        long intervalTicks = checkInterval * 20L;
        checkTaskId = plugin.getScheduler().runGlobalTimer(this::checkAllOnlinePlayers, 100L, intervalTicks);
    }

    /** 取消定时检查任务。 */
    public void shutdown() {
        if (checkTaskId != -1) {
            plugin.getScheduler().cancel(checkTaskId);
            checkTaskId = -1;
        }
    }

    /**
     * 检查所有在线玩家
     */
    private void checkAllOnlinePlayers() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            // PAPI 占位符解析切实体线程执行，避免异步线程解析
            plugin.getScheduler().runAtPlayer(player, () -> checkPlayer(player));
        }
    }

    /**
     * 检查单个玩家是否满足自动成为老玩家的条件
     */
    public void checkPlayer(Player player) {
        if (!enabled) return;

        UUID uuid = player.getUniqueId();

        // 如果已经授予过，跳过
        if (alreadyGranted.contains(uuid)) {
            return;
        }

        // 检查玩家是否已有老玩家权限
        String veteranPerm = plugin.getConfigManager().getConfig()
                .getString("invite_code.veteran_permission", "alinvite.veteran");
        if (player.hasPermission(veteranPerm)) {
            // 已有权限，记录为已授予，避免重复检查
            alreadyGranted.add(uuid);
            return;
        }

        // 通过 PAPI 获取在线时间
        double hours = getPlaytimeHours(player);
        if (hours < 0) {
            // PAPI 解析失败或占位符不存在，跳过
            return;
        }

        if (hours >= requiredHours) {
            // 达到要求，授予权限
            grantVeteranPermission(player, hours);
        }
    }

    /**
     * 获取玩家的在线时间（小时）
     * 通过 PlaceholderAPI 解析配置的占位符
     * @return 在线小时数，-1 表示解析失败
     */
    private double getPlaytimeHours(Player player) {
        boolean useBuiltin = plugin.getConfigManager().getConfig()
                .getBoolean("auto_veteran.use_builtin_statistic", true);
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                String result = PlaceholderAPI.setPlaceholders(player, playtimePlaceholder);
                if (!result.equals(playtimePlaceholder)) {
                    double rawValue = parsePlaytimeValue(result);
                    if (rawValue >= 0) {
                        return convertToHours(rawValue);
                    }
                }
            }

            if (useBuiltin) {
                // PLAY_ONE_MINUTE 使用游戏刻计数；读取发生在玩家实体线程。
                return player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 72000.0;
            }

            if (plugin.getConfigManager().getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("自动老玩家: PAPI 占位符 " + playtimePlaceholder
                        + " 未解析，且未启用内置 PLAY_ONE_MINUTE 回退，玩家: " + player.getName());
            }
        } catch (Exception e) {
            if (plugin.getConfigManager().getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("自动老玩家: 获取玩家 " + player.getName()
                        + " 的在线时间失败: " + e.getMessage());
            }
        }
        return -1;
    }

    /**
     * 解析 PAPI 返回的在线时间字符串为数值
     * 支持纯数字、带单位的字符串（如 "12.5 小时"、"72000"）
     */
    private double parsePlaytimeValue(String result) {
        if (result == null || result.isEmpty()) {
            return -1;
        }

        // 去除颜色代码
        String cleaned = result.replaceAll("§[0-9a-fk-or]", "").trim();

        // 尝试直接解析为数字
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
        }

        // 尝试提取字符串中的第一个数字
        // 匹配类似 "12.5 小时"、"72000 分钟" 等格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
        java.util.regex.Matcher matcher = pattern.matcher(cleaned);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored2) {
            }
        }

        return -1;
    }

    /**
     * 根据配置的单位将原始值转换为小时
     */
    private double convertToHours(double rawValue) {
        return switch (valueUnit) {
            case "minutes" -> rawValue / 60.0;
            case "seconds" -> rawValue / 3600.0;
            case "ticks" -> rawValue / 72000.0; // 20 ticks/s * 3600 s/h
            default -> rawValue; // "hours" 或未知，直接当作小时
        };
    }

    /**
     * 授予玩家老玩家权限
     */
    private void grantVeteranPermission(Player player, double hours) {
        // 立即标记为已授予，防止竞态条件导致重复执行
        alreadyGranted.add(player.getUniqueId());

        String command = grantCommand.replace("{player}", player.getName());
        final String finalCommand = command;

        plugin.getScheduler().runGlobal(() -> {
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            if (success) {
                plugin.getLogger().info("自动老玩家: " + player.getName()
                        + " 的在线时间达到 " + String.format("%.1f", hours) + " 小时，已授予老玩家权限");

                // 通知玩家
                String veteranPerm = plugin.getConfigManager().getConfig()
                        .getString("invite_code.veteran_permission", "alinvite.veteran");
                String notifyMsg = plugin.getConfigManager().getConfig()
                        .getString("auto_veteran.notify_message",
                                "&a恭喜！您的在线时间已达到要求，现在可以生成邀请码邀请新玩家了！");
                if (notifyMsg != null && !notifyMsg.isEmpty()) {
                    plugin.getScheduler().runAtPlayer(player, () ->
                        player.sendMessage(ConfigManager.colorize(notifyMsg, player)));
                }

                // 自动为玩家生成邀请码
                plugin.getInviteManager().generateInviteCode(player.getUniqueId());
            } else {
                // 命令执行失败，回滚标记，允许下次重试
                alreadyGranted.remove(player.getUniqueId());
                plugin.getLogger().warning("自动老玩家: 授予权限命令执行失败: " + finalCommand);
            }
        });
    }

    /**
     * 玩家加入时检查
     */
    public void onPlayerJoin(Player player) {
        if (!enabled) return;
        // 延迟几秒检查，等待 PAPI 数据加载；PAPI 解析在实体线程执行
        plugin.getScheduler().runAsyncDelayed(() ->
            plugin.getScheduler().runAtPlayer(player, () -> checkPlayer(player)), 60L);
    }

    /**
     * 重置玩家状态（用于 admin reset 命令）
     */
    public void resetPlayer(UUID uuid) {
        alreadyGranted.remove(uuid);
    }

    /**
     * 检查是否已启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取已授予的玩家数量
     */
    public int getGrantedCount() {
        return alreadyGranted.size();
    }
}
