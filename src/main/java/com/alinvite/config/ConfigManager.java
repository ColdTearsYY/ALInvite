package com.alinvite.config;

import com.alinvite.ALInvite;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ConfigManager {

    private static final int CURRENT_CONFIG_VERSION = 2;

    private final ALInvite plugin;
    private FileConfiguration config;
    private FileConfiguration menusConfig;
    private FileConfiguration langConfig;

    private File configFile;
    private File menusFile;
    private File langFile;

    public ConfigManager(ALInvite plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        loadConfig();
        loadMenus();
        loadLang();
    }

    private void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        
        migrateConfigIfNeeded();
        updateMissingConfigs();
    }

    private void loadMenus() {
        menusFile = new File(plugin.getDataFolder(), "menus.yml");
        if (!menusFile.exists()) {
            plugin.saveResource("menus.yml", false);
        }
        menusConfig = YamlConfiguration.loadConfiguration(menusFile);
        
        // 自动更新缺失的菜单配置项
        updateMissingMenus();
    }

    private void loadLang() {
        String locale = config.getString("language.locale", "zh_cn");
        String langFileName = locale + ".yml";

        File languagesFolder = new File(plugin.getDataFolder(), "languages");
        langFile = new File(languagesFolder, langFileName);

        if (!langFile.exists()) {
            if (!languagesFolder.exists()) {
                languagesFolder.mkdirs();
            }
            plugin.saveResource("languages/" + langFileName, false);
        }

        if (!langFile.exists()) {
            langFile = new File(languagesFolder, "zh_cn.yml");
            if (!langFile.exists()) {
                plugin.saveResource("languages/zh_cn.yml", false);
            }
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 自动更新缺失的语言配置项
        updateMissingLang();
    }

    /**
     * 自动更新缺失的菜单配置项
     */
    private void updateMissingMenus() {
        boolean menusUpdated = false;
        
        try {
            // 获取默认菜单配置（从jar包中的原始配置）
            FileConfiguration defaultMenus = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(
                    plugin.getResource("menus.yml"), 
                    java.nio.charset.StandardCharsets.UTF_8
                )
            );
            
            // 检查并添加缺失的菜单配置项
            menusUpdated |= updateConfigSection(defaultMenus, menusConfig, "main_menu");
            menusUpdated |= updateConfigSection(defaultMenus, menusConfig, "gift_shop");
            menusUpdated |= updateConfigSection(defaultMenus, menusConfig, "milestone_rewards");
            
            // 如果菜单配置有更新，保存文件
            if (menusUpdated) {
                try {
                    menusConfig.save(menusFile);
                    plugin.getLogger().info("已自动更新菜单配置文件，添加了缺失的配置项");
                } catch (IOException e) {
                    plugin.getLogger().warning("无法保存更新的菜单配置文件: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("无法加载默认菜单配置: " + e.getMessage());
        }
    }
    
    /**
     * 自动更新缺失的语言配置项
     */
    private void updateMissingLang() {
        boolean langUpdated = false;
        
        try {
            // 获取默认语言配置（从jar包中的原始配置）
            FileConfiguration defaultLang = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(
                    plugin.getResource("languages/zh_cn.yml"), 
                    java.nio.charset.StandardCharsets.UTF_8
                )
            );
            
            // 检查并添加缺失的语言配置项
            langUpdated |= updateConfigSection(defaultLang, langConfig, "commands.admin.contribution_deducted");
            langUpdated |= updateConfigSection(defaultLang, langConfig, "commands.admin.contribution_exchanged");
            langUpdated |= updateConfigSection(defaultLang, langConfig, "commands.admin.contribution_exchange_failed");
            langUpdated |= updateConfigSection(defaultLang, langConfig, "points_rebate.contribution_rebate_recorded");
            
            // 如果语言配置有更新，保存文件
            if (langUpdated) {
                try {
                    langConfig.save(langFile);
                    plugin.getLogger().info("已自动更新语言配置文件，添加了缺失的配置项");
                } catch (IOException e) {
                    plugin.getLogger().warning("无法保存更新的语言配置文件: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("无法加载默认语言配置: " + e.getMessage());
        }
    }
    
    /**
     * 检查并执行配置迁移
     */
    private void migrateConfigIfNeeded() {
        int configVersion = config.getInt("version", 1);
        
        if (configVersion < CURRENT_CONFIG_VERSION) {
            plugin.getLogger().info("检测到配置文件版本 " + configVersion + "，正在迁移到版本 " + CURRENT_CONFIG_VERSION + "...");
            
            for (int fromVersion = configVersion; fromVersion < CURRENT_CONFIG_VERSION; fromVersion++) {
                boolean migrated = migrateToVersion(fromVersion, fromVersion + 1);
                if (migrated) {
                    plugin.getLogger().info("配置已从 v" + fromVersion + " 迁移到 v" + (fromVersion + 1));
                }
            }
            
            config.set("version", CURRENT_CONFIG_VERSION);
            try {
                config.save(configFile);
                plugin.getLogger().info("配置迁移完成！");
            } catch (IOException e) {
                plugin.getLogger().warning("无法保存迁移后的配置文件: " + e.getMessage());
            }
        }
    }
    
    /**
     * 执行从 fromVersion 到 toVersion 的迁移
     */
    private boolean migrateToVersion(int fromVersion, int toVersion) {
        switch (fromVersion) {
            case 1:
                return migrateV1toV2();
            default:
                plugin.getLogger().warning("未知的迁移路径: v" + fromVersion + " -> v" + toVersion);
                return false;
        }
    }
    
    /**
     * v1 到 v2 的迁移
     * v2 新增了 gift_shop.gifts 配置节（如果玩家没有定义的话）
     */
    private boolean migrateV1toV2() {
        FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
            new java.io.InputStreamReader(
                plugin.getResource("config.yml"), 
                java.nio.charset.StandardCharsets.UTF_8
            )
        );
        
        if (!config.contains("gift_shop.gifts") && defaultConfig.contains("gift_shop.gifts")) {
            Object defaultGifts = defaultConfig.get("gift_shop.gifts");
            config.set("gift_shop.gifts", defaultGifts);
            plugin.getLogger().info("迁移: 添加 gift_shop.gifts 配置节");
            return true;
        }
        
        return false;
    }
    
    /**
     * 自动更新缺失的配置项
     * 只添加缺失的配置，不会修改用户已设置的配置
     */
    private void updateMissingConfigs() {
        boolean configUpdated = false;
        
        // 获取默认配置（从jar包中的原始配置）
        FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
            new java.io.InputStreamReader(
                plugin.getResource("config.yml"), 
                java.nio.charset.StandardCharsets.UTF_8
            )
        );
        
        // 检查并添加缺失的配置项
        configUpdated |= updateConfigSection(defaultConfig, config, "points_rebate.rebate_rates.contribution");
        configUpdated |= updateConfigSection(defaultConfig, config, "points_rebate.points_command");
        configUpdated |= updateConfigSection(defaultConfig, config, "language");
        configUpdated |= updateConfigSection(defaultConfig, config, "database");
        configUpdated |= updateConfigSection(defaultConfig, config, "milestones");
        configUpdated |= updateConfigSection(defaultConfig, config, "gifts");
        
        // 如果配置有更新，保存文件
        if (configUpdated) {
            try {
                config.save(configFile);
                plugin.getLogger().info("已自动更新配置文件，添加了缺失的配置项");
            } catch (IOException e) {
                plugin.getLogger().warning("无法保存更新的配置文件: " + e.getMessage());
            }
        }
    }
    
    /**
     * 更新配置节，只添加缺失的配置项
     */
    private boolean updateConfigSection(FileConfiguration defaultConfig, FileConfiguration currentConfig, String path) {
        boolean updated = false;
        
        // 如果当前配置中不存在该路径，则从默认配置复制
        if (!currentConfig.contains(path)) {
            Object defaultValue = defaultConfig.get(path);
            if (defaultValue != null) {
                currentConfig.set(path, defaultValue);
                plugin.getLogger().info("添加缺失配置项: " + path);
                updated = true;
            }
        } else if (currentConfig.isConfigurationSection(path)) {
            // 如果是配置节，递归检查子项
            org.bukkit.configuration.ConfigurationSection defaultSection = defaultConfig.getConfigurationSection(path);
            org.bukkit.configuration.ConfigurationSection currentSection = currentConfig.getConfigurationSection(path);
            
            if (defaultSection != null && currentSection != null) {
                for (String key : defaultSection.getKeys(true)) {
                    String fullKey = path + "." + key;
                    if (!currentConfig.contains(fullKey)) {
                        Object defaultValue = defaultConfig.get(fullKey);
                        currentConfig.set(fullKey, defaultValue);
                        plugin.getLogger().info("添加缺失配置项: " + fullKey);
                        updated = true;
                    }
                }
            }
        }
        
        return updated;
    }

    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存 config.yml: " + e.getMessage());
        }
    }

    public void saveMenus() {
        try {
            menusConfig.save(menusFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存 menus.yml: " + e.getMessage());
        }
    }

    public void saveLang() {
        try {
            langConfig.save(langFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存 lang.yml: " + e.getMessage());
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public FileConfiguration getMenusConfig() {
        return menusConfig;
    }

    public FileConfiguration getLangConfig() {
        return langConfig;
    }

    public String getMessage(String path) {
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        String msg = langConfig.getString(path, "&cMessage not found: " + path);
        return colorize(prefix + msg);
    }

    /**
     * 获取语言文件消息（支持 PlaceholderAPI 变量解析）
     * @param path 语言文件路径
     * @param player 目标玩家（用于解析 PAPI 占位符）
     */
    public String getMessage(String path, Player player) {
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        String msg = langConfig.getString(path, "&cMessage not found: " + path);
        return colorize(prefix + msg, player);
    }

    public String getMessageRaw(String path) {
        return colorize(langConfig.getString(path, "&cMessage not found: " + path));
    }

    /**
     * 获取语言文件原始消息（不带前缀，支持 PlaceholderAPI 变量解析）
     * @param path 语言文件路径
     * @param player 目标玩家（用于解析 PAPI 占位符）
     */
    public String getMessageRaw(String path, Player player) {
        return colorize(langConfig.getString(path, "&cMessage not found: " + path), player);
    }

    public List<String> getMessageList(String path) {
        List<String> list = langConfig.getStringList(path);
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        return list.stream().map(s -> colorize(prefix + s)).toList();
    }

    /**
     * 获取语言文件消息列表（支持 PlaceholderAPI 变量解析）
     * @param path 语言文件路径
     * @param player 目标玩家（用于解析 PAPI 占位符）
     */
    public List<String> getMessageList(String path, Player player) {
        List<String> list = langConfig.getStringList(path);
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        return list.stream().map(s -> colorize(prefix + s, player)).toList();
    }

    public static String colorize(String text) {
        if (text == null) return "";
        text = text.replace("&", "§");
        try {
            net.kyori.adventure.text.Component component = miniMessage.deserialize(text);
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component);
        } catch (Exception e) {
            return text;
        }
    }

    public static String colorize(String text, Player player) {
        if (text == null) return "";
        // 先处理PlaceholderAPI变量
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            text = PlaceholderAPI.setPlaceholders(player, text);
        }
        text = text.replace("&", "§");
        try {
            net.kyori.adventure.text.Component component = miniMessage.deserialize(text);
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component);
        } catch (Exception e) {
            return text;
        }
    }

    public static net.kyori.adventure.text.Component miniMessage(String text) {
        if (text == null) return net.kyori.adventure.text.Component.empty();
        text = text.replace("&", "§");
        try {
            return miniMessage.deserialize(text);
        } catch (Exception e) {
            return net.kyori.adventure.text.Component.text(text);
        }
    }

    public static net.kyori.adventure.text.Component miniMessage(String text, Player player) {
        if (text == null) return net.kyori.adventure.text.Component.empty();
        // 先处理PlaceholderAPI变量
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            text = PlaceholderAPI.setPlaceholders(player, text);
        }
        text = text.replace("&", "§");
        try {
            return miniMessage.deserialize(text);
        } catch (Exception e) {
            return net.kyori.adventure.text.Component.text(text);
        }
    }



    private static final MiniMessage miniMessage = MiniMessage.miniMessage();
}
