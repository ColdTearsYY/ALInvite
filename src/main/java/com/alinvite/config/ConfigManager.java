package com.alinvite.config;

import com.alinvite.ALInvite;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 配置与语言文件管理。
 *  - 启动/加载时对 config.yml 与语言文件做"只补缺失 key"的全量合并（尊重服主已有修改）；
 *  - colorize 统一收口：只转换合法颜色码（不误伤正文 & 字符），含 MiniMessage 标签时才走 MiniMessage 解析。
 * 菜单配置（menus/）由 gui.MenuConfigLoader 全权负责。
 */
public class ConfigManager {

    private static final int CURRENT_CONFIG_VERSION = 3;

    private final ALInvite plugin;
    private FileConfiguration config;
    private FileConfiguration databaseConfig;
    private FileConfiguration langConfig;

    private File configFile;
    private File databaseFile;
    private File langFile;

    private java.time.ZoneId timeZone;

    public ConfigManager(ALInvite plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        backupAndRegenerateLegacyConfigs();
        loadConfig();
        loadDatabase();
        loadLang();
        loadServerIdentity();
    }

    /**
     * 2.0.0 起配置结构全面重构，旧版配置无法直接沿用：
     * 检测到旧版配置（version < 3）时，整体备份到 backup/ 目录后删除，
     * 随后由默认加载流程从 jar 重新生成全套新配置。
     *
     * 新体系（version >= 3）之后正常更新只走"只补缺失"合并升级，不会再触发清空重生成，
     * 不影响后续版本的服主自定义配置。
     */
    private void backupAndRegenerateLegacyConfigs() {
        File root = plugin.getDataFolder();
        File existing = new File(root, "config.yml");
        if (!existing.exists()) {
            return; // 全新安装
        }
        try {
            YamlConfiguration current = YamlConfiguration.loadConfiguration(existing);
            int version = current.getInt("version", 1);
            if (version >= CURRENT_CONFIG_VERSION) {
                return; // 已是新体系
            }

            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            File backupDir = new File(root, "backup/v1.2.x-" + stamp);
            backupDir.mkdirs();

            copyFile(existing, new File(backupDir, "config.yml"));
            copyFile(new File(root, "menus.yml"), new File(backupDir, "menus.yml"));
            copyFile(new File(root, "database.yml"), new File(backupDir, "database.yml"));
            copyTree(new File(root, "menus"), new File(backupDir, "menus"));
            copyTree(new File(root, "menus_en"), new File(backupDir, "menus_en"));
            copyTree(new File(root, "languages"), new File(backupDir, "languages"));

            // 删除旧配置，交给默认流程重新生成
            existing.delete();
            new File(root, "menus.yml").delete();
            new File(root, "database.yml").delete();
            deleteRecursively(new File(root, "menus"));
            deleteRecursively(new File(root, "menus_en"));
            deleteRecursively(new File(root, "languages"));

            plugin.getLogger().warning("检测到 2.0.0 之前的旧版配置（结构已不兼容），"
                + "已整体备份到 " + backupDir.getPath() + " 并重新生成默认配置。");
        } catch (Exception e) {
            plugin.getLogger().warning("旧配置备份/重生成失败，将继续使用现有配置: " + e.getMessage());
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        if (!from.exists() || !from.isFile()) {
            return;
        }
        java.nio.file.Files.copy(from.toPath(), to.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(File fromDir, File toDir) throws IOException {
        if (!fromDir.exists() || !fromDir.isDirectory()) {
            return;
        }
        File[] files = fromDir.listFiles();
        if (files == null) {
            return;
        }
        toDir.mkdirs();
        for (File file : files) {
            if (file.isDirectory()) {
                copyTree(file, new File(toDir, file.getName()));
            } else {
                copyFile(file, new File(toDir, file.getName()));
            }
        }
    }

    private static void deleteRecursively(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteRecursively(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
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

        updateMissingLang();
    }

    /**
     * 加载独立的 database.yml（数据库 + Redis 跨服配置）。
     * 迁移：旧版 database 段写在 config.yml 里，首次加载时自动搬移并从 config.yml 中移除。
     */
    private void loadDatabase() {
        databaseFile = new File(plugin.getDataFolder(), "database.yml");
        if (!databaseFile.exists()) {
            if (config.contains("database")) {
                YamlConfiguration migrated = new YamlConfiguration();
                ConfigurationSection legacy = config.getConfigurationSection("database");
                if (legacy != null) {
                    copySectionValues(legacy, migrated.createSection("database"));
                }
                FileConfiguration defaults = loadBuiltin("database.yml");
                if (defaults != null) {
                    mergeMissing(defaults, migrated, null);
                }
                try {
                    migrated.save(databaseFile);
                    config.set("database", null);
                    config.save(configFile);
                    plugin.getLogger().info("已将 config.yml 中的 database 配置迁移到 database.yml");
                } catch (IOException e) {
                    plugin.getLogger().warning("迁移 database 配置失败: " + e.getMessage());
                }
            } else {
                plugin.saveResource("database.yml", false);
            }
        }
        databaseConfig = YamlConfiguration.loadConfiguration(databaseFile);

        try {
            FileConfiguration defaults = loadBuiltin("database.yml");
            if (defaults != null && mergeMissing(defaults, databaseConfig, null)) {
                databaseConfig.save(databaseFile);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("无法合并默认数据库配置: " + e.getMessage());
        }
    }

    private void copySectionValues(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            Object value = from.get(key);
            if (value instanceof ConfigurationSection sub) {
                copySectionValues(sub, to.createSection(key));
            } else {
                to.set(key, value);
            }
        }
    }

    /** 服务器标识与时区。 */
    private void loadServerIdentity() {
        String tz = config.getString("time.timezone", "Asia/Shanghai");
        try {
            timeZone = java.time.ZoneId.of(tz.trim());
        } catch (Exception e) {
            plugin.getLogger().warning("无效的时区配置: " + tz + "，已回退到系统默认时区");
            timeZone = java.time.ZoneId.systemDefault();
        }
    }

    /** 本服唯一 ID（集群内必须唯一）。 */
    public String getServerId() {
        String id = config.getString("serverid", null);
        if (id == null || id.isBlank()) {
            id = databaseConfig.getString("database.server_id", "server1");
        }
        return id;
    }

    /** 是否为主服（集群中建议仅一台为 true）。 */
    public boolean isMasterServer() {
        return config.getBoolean("server.master", false);
    }

    /** 服务器别称（跨服公告等展示用）。 */
    public String getServerAlias() {
        String alias = config.getString("serverName", null);
        return alias == null || alias.isBlank() ? getServerId() : alias;
    }

    /** 时区（影响返利记录时间等显示）。 */
    public java.time.ZoneId getTimeZone() {
        return timeZone == null ? java.time.ZoneId.systemDefault() : timeZone;
    }

    public FileConfiguration getDatabaseConfig() {
        return databaseConfig;
    }

    public void saveDatabase() {
        try {
            databaseConfig.save(databaseFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存 database.yml: " + e.getMessage());
        }
    }

    /**
     * 语言文件"只补缺失"合并：jar 内默认文件中磁盘缺失的 key 全部补入，已有 key 一律保留磁盘版。
     */
    private void updateMissingLang() {
        boolean langUpdated = false;

        try {
            FileConfiguration defaultLang = loadBuiltin("languages/zh_cn.yml");
            if (defaultLang != null) {
                langUpdated |= mergeMissing(defaultLang, langConfig, null);
            }
            String locale = config.getString("language.locale", "zh_cn");
            if (locale != null && !"zh_cn".equalsIgnoreCase(locale)) {
                FileConfiguration localized = loadBuiltin("languages/" + locale + ".yml");
                if (localized != null) {
                    langUpdated |= mergeMissing(localized, langConfig, null);
                }
            }

            if (langUpdated) {
                langConfig.save(langFile);
                plugin.getLogger().info("已自动更新语言配置文件，添加了缺失的配置项");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("无法合并默认语言配置: " + e.getMessage());
        }
    }

    /**
     * 配置"只补缺失"合并。
     */
    private void updateMissingConfigs() {
        boolean configUpdated = false;

        try {
            FileConfiguration defaultConfig = loadBuiltin("config.yml");
            if (defaultConfig != null) {
                configUpdated |= mergeMissing(defaultConfig, config, null);
            }

            if (configUpdated) {
                config.save(configFile);
                plugin.getLogger().info("已自动更新配置文件，添加了缺失的配置项");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("无法合并默认配置: " + e.getMessage());
        }
    }

    private FileConfiguration loadBuiltin(String path) {
        java.io.InputStream stream = plugin.getResource(path);
        if (stream == null) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    /** 递归合并：仅当磁盘缺失时写入默认值。返回是否有变更。 */
    private boolean mergeMissing(FileConfiguration defaults, FileConfiguration current, String path) {
        boolean updated = false;
        Iterable<String> keys = path == null ? defaults.getKeys(false) : defaults.getConfigurationSection(path).getKeys(false);

        for (String key : keys) {
            String fullKey = path == null ? key : path + "." + key;
            if (!current.contains(fullKey)) {
                Object value = defaults.get(fullKey);
                if (value != null) {
                    current.set(fullKey, value);
                    updated = true;
                }
            } else if (defaults.isConfigurationSection(fullKey) && current.isConfigurationSection(fullKey)) {
                updated |= mergeMissing(defaults, current, fullKey);
            }
        }
        return updated;
    }

    /** 确保版本戳为当前版本（旧体系配置已在备份重生成流程中处理）。 */
    private void migrateConfigIfNeeded() {
        if (config.getInt("version", CURRENT_CONFIG_VERSION) < CURRENT_CONFIG_VERSION) {
            config.set("version", CURRENT_CONFIG_VERSION);
            try {
                config.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().warning("无法保存配置版本号: " + e.getMessage());
            }
        }
    }

    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存 config.yml: " + e.getMessage());
        }
    }

    public void saveLang() {
        try {
            langConfig.save(langFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存语言文件: " + e.getMessage());
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public FileConfiguration getLangConfig() {
        return langConfig;
    }

    public String getMessage(String path) {
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        return colorize(prefix + rawMessage(path));
    }

    /** 语言值支持两种写法：单行字符串，或 YAML 列表（每项一行，按 
 拼接），方便服主直接编辑。 */
    private String rawMessage(String path) {
        if (langConfig.isList(path)) {
            return String.join("\n", langConfig.getStringList(path));
        }
        return langConfig.getString(path, "&cMessage not found: " + path);
    }

    /**
     * 获取语言文件消息（支持 PlaceholderAPI 变量解析）
     */
    public String getMessage(String path, Player player) {
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        return colorize(prefix + rawMessage(path), player);
    }

    public String getMessageRaw(String path) {
        return colorize(rawMessage(path));
    }

    public String getMessageRaw(String path, Player player) {
        return colorize(rawMessage(path), player);
    }

    public List<String> getMessageList(String path) {
        List<String> list = langConfig.getStringList(path);
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        return list.stream().map(s -> colorize(prefix + s)).toList();
    }

    public List<String> getMessageList(String path, Player player) {
        List<String> list = langConfig.getStringList(path);
        String prefix = langConfig.getString("prefix", config.getString("prefix", "&6[ALInvite] &r"));
        return list.stream().map(s -> colorize(prefix + s, player)).toList();
    }

    // ─── 颜色处理（全插件唯一收口） ───

    /** 只转换 & + 合法颜色字符，不误伤正文中的普通 & 字符。 */
    private static String convertAmpCodes(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length() && isColorCodeChar(text.charAt(i + 1))) {
                sb.append('§').append(text.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isColorCodeChar(char c) {
        return (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
            || (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O')
            || c == 'r' || c == 'R' || c == 'x' || c == 'X';
    }

    public static String colorize(String text) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty()) {
            return text;
        }
        String converted = convertAmpCodes(text);
        // 仅在含 MiniMessage 标签时才做完整解析（渲染高频路径的快路径）
        if (converted.indexOf('<') >= 0) {
            try {
                return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .serialize(MiniMessageHolder.MINI.deserialize(converted));
            } catch (Exception e) {
                return converted;
            }
        }
        return converted;
    }

    public static String colorize(String text, Player player) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty()) {
            return text;
        }
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            text = PlaceholderAPI.setPlaceholders(player, text);
        }
        return colorize(text);
    }

    public static net.kyori.adventure.text.Component miniMessage(String text) {
        if (text == null) {
            return net.kyori.adventure.text.Component.empty();
        }
        try {
            return MiniMessageHolder.MINI.deserialize(convertAmpCodes(text));
        } catch (Exception e) {
            return net.kyori.adventure.text.Component.text(text);
        }
    }

    public static net.kyori.adventure.text.Component miniMessage(String text, Player player) {
        if (text == null) {
            return net.kyori.adventure.text.Component.empty();
        }
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            text = PlaceholderAPI.setPlaceholders(player, text);
        }
        return miniMessage(text);
    }

    private static final class MiniMessageHolder {
        private static final net.kyori.adventure.text.minimessage.MiniMessage MINI =
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
    }
}
