package com.alinvite;

import com.alinvite.commands.CommandHandler;
import com.alinvite.config.ConfigManager;
import com.alinvite.database.DatabaseManager;
import com.alinvite.gui.MenuManager;
import com.alinvite.gui.MenuSessionManager;
import com.alinvite.listeners.InviteListener;
import com.alinvite.listeners.LuckPermsListener;
import com.alinvite.listeners.MenuListener;
import com.alinvite.listeners.PermissionGroupRewardListener;
import com.alinvite.manager.CacheManager;
import com.alinvite.manager.InviteManager;
import com.alinvite.manager.MilestoneManager;
import com.alinvite.manager.GiftManager;
import com.alinvite.manager.PointsRebateManager;
import com.alinvite.manager.LeaderboardManager;
import com.alinvite.manager.AutoVeteranManager;
import com.alinvite.placeholder.PlaceholderHook;
import com.alinvite.api.ALInviteAPI;
import com.alinvite.utils.ColorUtil;
import com.alinvite.utils.SchedulerUtils;
import com.alinvite.utils.ThreadPoolManager;
import com.alinvite.utils.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class ALInvite extends JavaPlugin {

    private static ALInvite instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private CacheManager cacheManager;
    private InviteManager inviteManager;
    private MilestoneManager milestoneManager;
    private GiftManager giftManager;
    private MenuManager menuManager;
    private MenuListener menuListener;
    private PermissionGroupRewardListener permissionGroupRewardListener;
    private CommandHandler commandHandler;
    private PointsRebateManager pointsRebateManager;
    private LeaderboardManager leaderboardManager;
    private AutoVeteranManager autoVeteranManager;
    private ThreadPoolManager threadPoolManager;
    private FoliaScheduler foliaScheduler;

    public static ALInvite getInstance() {
        return instance;
    }

    public MenuListener getMenuListener() {
        return menuListener;
    }

    public PermissionGroupRewardListener getPermissionGroupRewardListener() {
        return permissionGroupRewardListener;
    }

    public FoliaScheduler getFoliaScheduler() {
        return foliaScheduler;
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

            scheduleAnnouncementSync();
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
        if (leaderboardManager != null) {
            leaderboardManager.shutdown();
        }
        if (threadPoolManager != null) {
            threadPoolManager.shutdown();
        }
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
        getLogger().info(ColorUtil.translate("&6 &r"));
        getLogger().info(ColorUtil.translate("&6═══════════════════════════════════════════════════&r"));
    }

    private void printLoadStatus() {
        getLogger().info(ColorUtil.translate("&6═══════════════════════════════════════════════════&r"));

        int milestoneCount = milestoneManager.getMilestones().size();
        getLogger().info(ColorUtil.translate("&6  &a✓ &f里程碑配置     &7| &a已加载 &f" + milestoneCount + " &7个里程碑&r"));

        int giftCount = giftManager.getAllGifts().size();
        getLogger().info(ColorUtil.translate("&6  &a✓ &f礼包配置       &7| &a已加载 &f" + giftCount + " &7个礼包&r"));

        getLogger().info(ColorUtil.translate("&6  &a✓ &f菜单配置       &7| &a已加载 &f3 &7个菜单&r"));

        String dbType = configManager.getConfig().getString("database.type", "sqlite");
        getLogger().info(ColorUtil.translate("&6  &a✓ &f数据库连接     &7| &a已连接 &f(" + dbType.toUpperCase() + ")&r"));

        String pointsType = configManager.getConfig().getString("points.type", "playerpoints");
        getLogger().info(ColorUtil.translate("&6  &a✓ &f点券系统       &7| &a" + pointsType.toUpperCase() + "&r"));

        boolean announcementsEnabled = configManager.getConfig().getBoolean("announcements.cross_server_sync", true);
        String announceStatus = announcementsEnabled ? "&a已启用&r" : "&c已禁用";
        getLogger().info(ColorUtil.translate("&6  &a✓ &f跨服公告同步   &7| " + announceStatus + "&r"));

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
        foliaScheduler = new FoliaScheduler(this);
        threadPoolManager = new ThreadPoolManager(this);
        cacheManager = new CacheManager(this);
        inviteManager = new InviteManager(this);
        milestoneManager = new MilestoneManager(this);
        giftManager = new GiftManager(this);
        menuManager = new MenuManager(this);
        pointsRebateManager = new PointsRebateManager(this);
        leaderboardManager = new LeaderboardManager(this);
        autoVeteranManager = new AutoVeteranManager(this);
        // 设置 MenuSessionManager 的 plugin 引用用于调试
        MenuSessionManager.getInstance().setPlugin(this);
    }

    private void initCommands() {
        commandHandler = new CommandHandler(this);
        getCommand("alinvite").setExecutor(commandHandler);
        getCommand("alinvite").setTabCompleter(commandHandler);
    }

    private void initListeners() {
        menuListener = new MenuListener(this);
        permissionGroupRewardListener = new PermissionGroupRewardListener(this);
        Bukkit.getPluginManager().registerEvents(new InviteListener(this), this);
        Bukkit.getPluginManager().registerEvents(menuListener, this);
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

    private void scheduleAnnouncementSync() {
        if (!configManager.getConfig().getBoolean("announcements.cross_server_sync", true)) {
            return;
        }

        int interval = configManager.getConfig().getInt("announcements.sync_interval", 5) * 20;
        SchedulerUtils.runTaskTimerAsync(this, () -> {
            databaseManager.syncAnnouncements();
        }, interval, interval);
    }

    private void schedulePermissionGroupCheck() {
        if (!configManager.getConfig().getBoolean("permission_group_rewards.enabled", false)) {
            return;
        }

        int interval = configManager.getConfig().getInt("permission_group_rewards.check_interval", 10) * 20;
        SchedulerUtils.runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                permissionGroupRewardListener.checkOnlinePlayerPermissionGroup(player);
            }
        }, interval, interval);
    }

    public void reload() {
        try {
            databaseManager.close();
            configManager.loadAll();
            initDatabase();
            initManagers();
            // 重新加载自动老玩家配置
            if (autoVeteranManager != null) {
                autoVeteranManager.loadConfig();
            }
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

    public void checkGiftExpiration(org.bukkit.entity.Player player) {
        getGiftManager().checkGiftExpiration(player);
    }

    public int getGiftRemainingDays(java.util.UUID uuid) {
        return getGiftManager().getGiftRemainingDays(uuid).join();
    }
}
