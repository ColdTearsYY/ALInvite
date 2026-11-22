package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动老玩家管理器
 * 使用本插件持久化的累计在线时间，
 * 达到配置的小时数后自动授予老玩家 (veteran) 权限
 */
public class AutoVeteranManager {

    private final ALInvite plugin;
    private final Set<UUID> alreadyGranted = ConcurrentHashMap.newKeySet();
    private int checkTaskId = -1;
    private boolean enabled;
    private double requiredHours;   // 转换后的需求小时数
    private long checkInterval;
    private String grantCommand;
    static final String DEFAULT_NOTIFY_MESSAGE = "&a已获得老玩家资格。邀请码仍按所在组的累计在线规则发放，可在主菜单查看进度。";
    static final String LEGACY_INVITE_UNLOCK_MESSAGE = "&a恭喜！您的在线时间已达到要求，现在可以生成邀请码邀请新玩家了！";

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
        // 解析 playtime 配置（支持 "10h" / "30s" / "5m" 等格式）
        String playtimeRaw = plugin.getConfigManager().getConfig()
                .getString("auto_veteran.playtime", "10h");
        this.requiredHours = parsePlaytime(playtimeRaw);
        this.checkInterval = plugin.getConfigManager().getConfig()
                .getLong("auto_veteran.check_interval", 300);
        this.grantCommand = plugin.getConfigManager().getConfig()
                .getString("auto_veteran.grant_command", "lp user {player} permission set alinvite.veteran true");

        if (enabled) {
            plugin.getLogger().info("自动老玩家系统已启用");
            plugin.getLogger().info("  在线时间来源: ALInvite 内置持久化计时");
            plugin.getLogger().info("  需求时长: " + String.format("%.2f", requiredHours) + " 小时");
            plugin.getLogger().info("  检查间隔: " + checkInterval + " 秒");
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
            // 权限读取和后续玩家操作切回实体线程；时长只读已落库缓存。
            plugin.getScheduler().runAtPlayer(player, () -> checkPlayer(player));
        }
    }

    /**
     * 检查单个玩家是否满足自动成为老玩家的条件
     */
    public void checkPlayer(Player player) {
        if (!enabled) return;

        UUID uuid = player.getUniqueId();
        if (plugin.getBindingGateManager() != null && plugin.getBindingGateManager().isLocked(uuid)) {
            return;
        }


        String veteranPerm = plugin.getConfigManager().getConfig()
                .getString("invite_code.veteran_permission", "alinvite.veteran");
        boolean veteran = player.hasPermission(veteranPerm);

        double hours = getPlaytimeHours(player);
        if (hours < 0) {
            return;
        }

        if (!veteran) {
            if (hours >= requiredHours && alreadyGranted.add(uuid)) {
                grantVeteranPermission(player, hours);
            }
            return;
        }

        alreadyGranted.add(uuid);
    }

    /**
     * 获取玩家的在线时间（小时）
     * 读取内置计时缓存；冷缓存异步加载，当前检查暂缓。
     * @return 已确认在线小时数，-1 表示尚未加载
     */
    private double getPlaytimeHours(Player player) {
        long millis = plugin.getPlaytimeManager().cachedMillis(player.getUniqueId());
        return millis < 0 ? -1 : millis / 3600000.0;
    }


    /**
     * 授予玩家老玩家权限
     */
    private void grantVeteranPermission(Player player, double hours) {

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
                String configuredNotify = plugin.getConfigManager().getConfig()
                        .getString("auto_veteran.notify_message", DEFAULT_NOTIFY_MESSAGE);
                String notifyMsg = resolveNotifyMessage(configuredNotify);
                if (notifyMsg != null && !notifyMsg.isEmpty()) {
                    plugin.getScheduler().runAtPlayer(player, () ->
                        player.sendMessage(ConfigManager.colorize(notifyMsg, player)));
                }

            } else {
                // 命令执行失败，回滚标记，允许下次重试
                alreadyGranted.remove(player.getUniqueId());
                plugin.getLogger().warning("自动老玩家: 授予权限命令执行失败: " + finalCommand);
            }
        });
    }

    static String resolveNotifyMessage(String configured) {
        return LEGACY_INVITE_UNLOCK_MESSAGE.equals(configured) ? DEFAULT_NOTIFY_MESSAGE : configured;
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
