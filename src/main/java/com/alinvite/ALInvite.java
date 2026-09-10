package com.alinvite;

import com.alinvite.api.ALInviteAPI;
import com.alinvite.commands.CommandHandler;
import com.alinvite.config.ConfigManager;
import com.alinvite.database.DatabaseManager;
import com.alinvite.gui.MenuManager;
import com.alinvite.listeners.InviteListener;
import com.alinvite.listeners.LuckPermsListener;
import com.alinvite.listeners.PermissionGroupRewardListener;
import com.alinvite.manager.AutoVeteranManager;
import com.alinvite.manager.CacheManager;
import com.alinvite.manager.GiftManager;
import com.alinvite.manager.InviteManager;
import com.alinvite.manager.LeaderboardManager;
import com.alinvite.manager.MilestoneManager;
import com.alinvite.manager.PointsRebateManager;
import com.alinvite.manager.RewardService;
import com.alinvite.placeholder.PlaceholderHook;
import com.alinvite.redis.RedisManager;
import com.alinvite.scheduler.ALInviteScheduler;
import com.alinvite.sync.CrossServerSync;
import com.alinvite.utils.AsyncPool;
import com.alinvite.utils.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class ALInvite extends JavaPlugin {

    private static ALInvite instance;
    private ConfigManager configManager;
    private ALInviteScheduler scheduler;
    private DatabaseManager databaseManager;
    private CacheManager cacheManager;
    private RewardService rewardService;
    private InviteManager inviteManager;
    private MilestoneManager milestoneManager;
    private GiftManager giftManager;
    private MenuManager menuManager;
    private PermissionGroupRewardListener permissionGroupRewardListener;
    private CommandHandler commandHandler;
    private PointsRebateManager pointsRebateManager;
    private LeaderboardManager leaderboardManager;
    private AutoVeteranManager autoVeteranManager;
    private RedisManager redisManager;
    private CrossServerSync crossServerSync;
    private int permissionGroupCheckTaskId = -1;

    public static ALInvite getInstance() {
        return instance;
    }

    public ALInviteScheduler getScheduler() {
        return scheduler;
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public CrossServerSync getSync() {
        return crossServerSync;
    }

    public RewardService getRewardService() {
        return rewardService;
    }

    public PermissionGroupRewardListener getPermissionGroupRewardListener() {
        return permissionGroupRewardListener;
    }

    @Override
    public void onEnable() {
        try {
            instance = this;

            if (!initConfig()) {
                getLogger().severe("配置文件加载失败！插件禁用。");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            AsyncPool.init(getConfigManager().getConfig().getInt("performance.io_threads", 4));
            scheduler = new ALInviteScheduler(this);

            if (!initDatabase()) {
                getLogger().severe("数据库初始化失败！插件禁用。");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            printBanner();

            initManagers();
            initCommands();
            initListeners();
            initPlaceholder();
            initAPI();

            schedulePermissionGroupCheck();

            printLoadStatus();
        } catch (Exception e) {
            getLogger().severe("插件启动过程中发生严重错误！");
            getLogger().severe("错误信息: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // 1. 回收所有调度任务（定时器/延迟动作），防止停服后残留任务操作失效的 Inventory
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        // 2. 清理 GUI 状态
        if (menuManager != null) {
            menuManager.shutdown();
        }
        // 3. 停止各管理器的定时任务
        if (leaderboardManager != null) {
            leaderboardManager.shutdown();
        }
        if (autoVeteranManager != null) {
            autoVeteranManager.shutdown();
        }
        // 4. 关闭 Redis 与 IO 线程池、数据库
        if (redisManager != null) {
            redisManager.close();
        }
        AsyncPool.shutdown();
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (cacheManager != null) {
            cacheManager.clear();
        }
        getLogger().info("ALInvite 插件已禁用。");
    }

    private void printBanner() {
        getLogger().info(ColorUtil.translate("&6═══════════════════════════════════════════════════&r"));
        getLogger().info(ColorUtil.translate("&6 &r"));
        getLogger().info(ColorUtil.translate("&6   &f▪ &e插件名称 &7» &fALInvite &7- &e邀请激励系统&r"));
        getLogger().info(ColorUtil.translate("&6   &f▪ &e插件版本 &7» &f" + getDescription().getVersion() + "&r"));
        getLogger().info(ColorUtil.translate("&6   &f▪ &e支持版本 &7» &f1.20.1 &7- &f1.21.11&r"));
        getLogger().info(ColorUtil.translate("&6   &f▪ &e数据库类型 &7» &f" + configManager.getConfig().getString("database.type", "sqlite").toUpperCase() + "&r"));
        getLogger().info(ColorUtil.translate("&6   &f▪ &e调度后端 &7» &f" + (scheduler != null && scheduler.isFolia() ? "Folia" : "传统调度器") + "&r"));
        getLogger().info(ColorUtil.translate("&6   &f▪ &e服务器标识 &7» &f" + configManager.getServerAlias() + " &7(" + configManager.getServerId() + (configManager.isMasterServer() ? " / 主服" : "") + ")&r"));
        getLogger().info(ColorUtil.translate("&6 &r"));
        getLogger().info(ColorUtil.translate("&6═══════════════════════════════════════════════════&r"));
    }

    private void printLoadStatus() {
        getLogger().info(ColorUtil.translate("&6═══════════════════════════════════════════════════&r"));

        int milestoneCount = milestoneManager.getMilestones().size();
        getLogger().info(ColorUtil.translate("&6  &a✓ &f里程碑配置     &7| &a已加载 &f" + milestoneCount + " &7个里程碑&r"));

        int giftCount = giftManager.getAllGifts().size();
        getLogger().info(ColorUtil.translate("&6  &a✓ &f礼包配置       &7| &a已加载 &f" + giftCount + " &7个礼包&r"));

        int menuCount = menuManager.getLoader().getAll().size();
        getLogger().info(ColorUtil.translate("&6  &a✓ &f菜单配置       &7| &a已加载 &f" + menuCount + " &7个菜单&r"));

        String pointsType = configManager.getConfig().getString("points.type", "playerpoints");
        getLogger().info(ColorUtil.translate("&6  &a✓ &f点券系统       &7| &a" + pointsType.toUpperCase() + "&r"));

        boolean announcementsEnabled = configManager.getConfig().getBoolean("announcements.enabled", true);
        String announceStatus = announcementsEnabled ? "&a已启用&r" : "&c已禁用";
        getLogger().info(ColorUtil.translate("&6  &a✓ &f里程碑公告     &7| " + announceStatus + "&r"));

        boolean permissionRewardsEnabled = configManager.getConfig().getBoolean("permission_group_rewards.enabled", false);
        String permRewardStatus = permissionRewardsEnabled ? "&a已启用" : "&c已禁用";
        getLogger().info(ColorUtil.translate("&6  &a✓ &f权限组奖励     &7| " + permRewardStatus + "&r"));

        boolean placeholderEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        String placeholderStatus = placeholderEnabled ? "&a已连接" : "&c未安装";
        getLogger().info(ColorUtil.translate("&6  &a✓ &fPlaceholderAPI &7| " + placeholderStatus + "&r"));

        boolean autoVeteranEnabled = autoVeteranManager != null && autoVeteranManager.isEnabled();
        String autoVeteranStatus = autoVeteranEnabled ? "&a已启用" : "&c已禁用";
        getLogger().info(ColorUtil.translate("&6  &a✓ &f自动老玩家     &7| " + autoVeteranStatus + "&r"));

        getLogger().info(ColorUtil.translate("&6 &r"));
        getLogger().info(ColorUtil.translate("&6  &a✔ &fALInvite &7插件已成功启用！&r"));
        getLogger().info(ColorUtil.translate("&6 &r"));
        getLogger().info(ColorUtil.translate("&6═══════════════════════════════════════════════════&r"));
    }

    private boolean initConfig() {
        try {
            configManager = new ConfigManager(this);
            configManager.loadAll();
            return true;
        } catch (Exception e) {
            getLogger().severe("加载配置文件时出错: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean initDatabase() {
        try {
            databaseManager = new DatabaseManager(this);
            databaseManager.init();
            return true;
        } catch (Exception e) {
            getLogger().severe("初始化数据库时出错: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void initManagers() {
        // 重建前先停掉旧管理器的定时任务，防止 reload 后旧定时器泄漏
        if (leaderboardManager != null) {
            leaderboardManager.shutdown();
        }
        if (autoVeteranManager != null) {
            autoVeteranManager.shutdown();
        }

        cacheManager = new CacheManager(this);
        rewardService = new RewardService(this);
        inviteManager = new InviteManager(this);
        milestoneManager = new MilestoneManager(this);
        giftManager = new GiftManager(this);
        menuManager = new MenuManager(this);
        pointsRebateManager = new PointsRebateManager(this);
        leaderboardManager = new LeaderboardManager(this);
        autoVeteranManager = new AutoVeteranManager(this);
        crossServerSync = new CrossServerSync(this);
        // 写穿透：所有数据库变更立即失效本机缓存并广播集群（ALFriends 同款，零延迟）
        databaseManager.setDataChangeListener(crossServerSync::onDataChanged);
        initRedis();
    }

    /** 初始化 Redis 跨服同步（database.yml redis 段，默认关闭）。 */
    private void initRedis() {
        if (!configManager.getDatabaseConfig().getBoolean("redis.enabled", false)) {
            return;
        }
        String host = configManager.getDatabaseConfig().getString("redis.host", "localhost");
        int port = configManager.getDatabaseConfig().getInt("redis.port", 6379);
        String password = configManager.getDatabaseConfig().getString("redis.password", "");
        String channel = configManager.getDatabaseConfig().getString("redis.channel", "alinvite_channel");
        redisManager = new RedisManager(this, host, port, password, channel);
        redisManager.init(crossServerSync::handleIncoming);
    }

    private void initCommands() {
        commandHandler = new CommandHandler(this);
        getCommand("alinvite").setExecutor(commandHandler);
        getCommand("alinvite").setTabCompleter(commandHandler);
    }

    private void initListeners() {
        permissionGroupRewardListener = new PermissionGroupRewardListener(this);
        Bukkit.getPluginManager().registerEvents(new InviteListener(this), this);
        Bukkit.getPluginManager().registerEvents(menuManager.getClickListener(), this);
        Bukkit.getPluginManager().registerEvents(menuManager.getInputService(), this);
        Bukkit.getPluginManager().registerEvents(permissionGroupRewardListener, this);
        Bukkit.getPluginManager().registerEvents(new LuckPermsListener(this, permissionGroupRewardListener), this);
    }

    private void initPlaceholder() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
            getLogger().info("PlaceholderAPI 集成已启用。");
        }
    }

    private void initAPI() {
        ALInviteAPI.init(this);
    }

    private void schedulePermissionGroupCheck() {
        if (!configManager.getConfig().getBoolean("permission_group_rewards.enabled", false)) {
            return;
        }

        int interval = configManager.getConfig().getInt("permission_group_rewards.check_interval", 10) * 20;
        permissionGroupCheckTaskId = scheduler.runGlobalTimer(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                permissionGroupRewardListener.checkOnlinePlayerPermissionGroup(player);
            }
        }, interval, interval);
    }

    public void reload() {
        try {
            // 停旧定时任务
            if (permissionGroupCheckTaskId != -1 && scheduler != null) {
                scheduler.cancel(permissionGroupCheckTaskId);
                permissionGroupCheckTaskId = -1;
            }
            // 注销所有旧监听器（持有旧 Manager 引用，不注销会导致菜单不可点）
            org.bukkit.event.HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);

            databaseManager.close();
            configManager.loadAll();
            initDatabase();
            initManagers();
            initListeners();
            initPlaceholder();
            schedulePermissionGroupCheck();
        } catch (Exception e) {
            getLogger().severe("Reload failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public InviteManager getInviteManager() {
        return inviteManager;
    }

    public MilestoneManager getMilestoneManager() {
        return milestoneManager;
    }

    public GiftManager getGiftManager() {
        return giftManager;
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }

    public com.alinvite.gui.MenuConfigLoader getMenuConfigLoader() {
        return menuManager != null ? menuManager.getLoader() : null;
    }

    public com.alinvite.utils.PlaceholderResolver getPlaceholderResolver() {
        return menuManager != null ? menuManager.getPlaceholderResolver() : null;
    }

    public CommandHandler getCommandHandler() {
        return commandHandler;
    }

    public PointsRebateManager getPointsRebateManager() {
        return pointsRebateManager;
    }

    public LeaderboardManager getLeaderboardManager() {
        return leaderboardManager;
    }

    public AutoVeteranManager getAutoVeteranManager() {
        return autoVeteranManager;
    }

    /** 兼容旧调用（InviteListener 等）：礼包过期检查。 */
    public java.util.concurrent.CompletableFuture<Boolean> checkGiftExpiration(Player player) {
        return giftManager != null ? giftManager.checkGiftExpiration(player)
            : java.util.concurrent.CompletableFuture.completedFuture(false);
    }

    /** 兼容旧调用：礼包剩余天数。 */
    public java.util.concurrent.CompletableFuture<Integer> getGiftRemainingDays(java.util.UUID uuid) {
        return giftManager != null ? giftManager.getGiftRemainingDays(uuid)
            : java.util.concurrent.CompletableFuture.completedFuture(-1);
    }
}
