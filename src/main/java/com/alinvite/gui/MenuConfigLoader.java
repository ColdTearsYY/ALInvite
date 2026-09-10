package com.alinvite.gui;

import com.alinvite.ALInvite;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * menus/ 目录加载器：一菜一文件、启动校验、config_version 合并升级（只补缺失、备份旧文件）、
 * 旧版单文件 menus.yml 自动迁移。加载失败时保留旧快照，绝不让 reload 把插件打残。
 */
public class MenuConfigLoader {

    public static final int CONFIG_VERSION = 1;
    public static final List<String> DEFAULT_MENUS = List.of(MenuNames.MAIN, MenuNames.VETERAN, MenuNames.SHOP, MenuNames.REBATE_HISTORY);
    private static final List<String> LEGACY_MENU_KEYS = List.of("main_menu", "veteran_menu", "shop_menu");

    private final ALInvite plugin;
    private volatile Map<String, MenuConfig> menus = Map.of();
    private volatile YamlConfiguration mergedRaw = new YamlConfiguration();

    public MenuConfigLoader(ALInvite plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        this.menus = Map.copyOf(loadFromDisk());
        YamlConfiguration merged = new YamlConfiguration();
        for (MenuConfig config : menus.values()) {
            merged.set(config.getName(), rawSection(config.getName()));
        }
        this.mergedRaw = merged;
    }

    public void reload() {
        loadAll();
    }

    public MenuConfig get(String name) {
        return name == null ? null : menus.get(name);
    }

    public Map<String, MenuConfig> getAll() {
        return menus;
    }

    /** 供 PAPI 扫描等工具使用：所有菜单合并后的原始配置。 */
    public YamlConfiguration getMergedRaw() {
        return mergedRaw;
    }

    private String menuDir() {
        String locale = plugin.getConfigManager().getConfig().getString("language.locale", "zh_cn");
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("en") ? "menus_en" : "menus";
    }

    private File menuDirFile() {
        return new File(plugin.getDataFolder(), menuDir());
    }

    private Map<String, MenuConfig> loadFromDisk() {
        File dir = menuDirFile();
        if (!dir.exists() && !migrateLegacy(dir)) {
            dir = menuDirFile();
            dir.mkdirs();
            for (String menuName : DEFAULT_MENUS) {
                saveDefaultResource(dir, menuName);
            }
        }

        Map<String, MenuConfig> loaded = new LinkedHashMap<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) {
            return loaded;
        }
        Arrays.sort(files, java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        List<String> allErrors = new ArrayList<>();
        for (File file : files) {
            FileConfiguration disk = YamlConfiguration.loadConfiguration(file);
            upgradeIfNeeded(file, disk);
            for (String menuName : disk.getKeys(false)) {
                if ("config_version".equals(menuName)) {
                    continue;
                }
                MenuConfig config = parseMenu(menuName, disk);
                List<String> errors = new ArrayList<>();
                validate(config, errors);
                if (!errors.isEmpty()) {
                    allErrors.add("[" + file.getName() + "] 菜单 " + menuName + " 配置错误:");
                    allErrors.addAll(errors.stream().map(e -> "  - " + e).toList());
                    continue;
                }
                config.freeze();
                loaded.put(MenuConfigLoader.normalizeName(menuName), config);
            }
        }
        if (!allErrors.isEmpty()) {
            plugin.getLogger().warning("========== 菜单配置校验失败 " + allErrors.size() + " 条 ==========");
            allErrors.forEach(line -> plugin.getLogger().warning(line));
            plugin.getLogger().warning("未通过校验的菜单已跳过，其余菜单正常加载。");
        }
        return loaded;
    }

    /**
     * 旧版单文件 menus.yml 迁移：解析服主已改内容 → 生成 menus/*.yml → 原文件备份为 menus.yml.bak。
     */
    private boolean migrateLegacy(File dir) {
        File legacyFile = new File(plugin.getDataFolder(), "menus.yml");
        if (!legacyFile.exists()) {
            return false;
        }
        FileConfiguration legacy = YamlConfiguration.loadConfiguration(legacyFile);
        String backgroundMaterial = legacy.getString("background.material", "BLACK_STAINED_GLASS_PANE");
        String backgroundName = legacy.getString("background.name", " ");

        dir.mkdirs();
        boolean migratedAny = false;
        for (String menuName : LEGACY_MENU_KEYS) {
            ConfigurationSection section = legacy.getConfigurationSection(menuName);
            YamlConfiguration merged;
            if (section != null) {
                merged = overlayLegacyOntoDefault(menuName, legacy);
            } else {
                merged = loadBuiltinResource(menuDir() + "/" + menuName + ".yml");
                if (merged == null) {
                    continue;
                }
            }
            merged.set("config_version", CONFIG_VERSION);
            ConfigurationSection menuSection = merged.getConfigurationSection(menuName);
            if (menuSection != null) {
                menuSection.set("background.material", backgroundMaterial);
                menuSection.set("background.name", backgroundName);
            }
            // 旧版结构已全部转换为 items/triggers，移除冗余节
            merged.set(menuName + ".buttons", null);
            merged.set(menuName + ".shape2", null);
            try {
                merged.save(new File(dir, menuName + ".yml"));
                migratedAny = true;
            } catch (Exception e) {
                plugin.getLogger().warning("迁移菜单 " + menuName + " 失败: " + e.getMessage());
            }
        }
        File backup = new File(plugin.getDataFolder(), "menus.yml.bak");
        if (backup.exists()) {
            backup.delete();
        }
        if (legacyFile.renameTo(backup)) {
            plugin.getLogger().info("已将旧版 menus.yml 迁移到 menus/ 目录，原文件备份为 menus.yml.bak（自定义内容已保留）");
        }
        return migratedAny;
    }

    /** jar 内置默认配置为底，磁盘旧版自定义内容覆盖其上（只覆盖存在的键）。 */
    private YamlConfiguration overlayLegacyOntoDefault(String menuName, FileConfiguration legacy) {
        YamlConfiguration merged = loadBuiltinResource(menuDir() + "/" + menuName + ".yml");
        if (merged == null) {
            merged = new YamlConfiguration();
        }
        ConfigurationSection legacySection = legacy.getConfigurationSection(menuName);
        if (legacySection != null) {
            copyExisting(legacySection, merged.createSection(menuName));
        }

        // 旧版 buttons 转换为 items：
        //  - 内置已有的键：仅补 triggers.left（旧 action 单值）；
        //  - 服主自定义新增的键：整键转换为 items.X，避免迁移丢内容。
        ConfigurationSection buttons = legacy.getConfigurationSection(menuName + ".buttons");
        if (buttons != null) {
            for (String key : buttons.getKeys(false)) {
                String buttonPath = menuName + ".buttons." + key;
                String itemsPath = menuName + ".items." + key;
                String action = legacy.getString(buttonPath + ".action", "");
                if (!merged.contains(itemsPath)) {
                    merged.set(itemsPath + ".material", legacy.getString(buttonPath + ".material", "STONE"));
                    if (legacy.contains(buttonPath + ".custom-model-data")) {
                        merged.set(itemsPath + ".custom_model_data", legacy.getInt(buttonPath + ".custom-model-data"));
                    }
                    merged.set(itemsPath + ".name", legacy.getString(buttonPath + ".name", ""));
                    merged.set(itemsPath + ".lore", legacy.getStringList(buttonPath + ".lore"));
                    if (legacy.contains(buttonPath + ".dynamic")) {
                        merged.set(itemsPath + ".dynamic", legacy.getBoolean(buttonPath + ".dynamic"));
                    }
                    ConfigurationSection states = legacy.getConfigurationSection(buttonPath + ".state_overrides");
                    if (states != null) {
                        for (String state : states.getKeys(false)) {
                            String from = buttonPath + ".state_overrides." + state;
                            String to = itemsPath + ".states." + state;
                            merged.set(to + ".material", legacy.getString(from + ".material"));
                            merged.set(to + ".name", legacy.getString(from + ".name"));
                            merged.set(to + ".lore", legacy.getStringList(from + ".lore"));
                            if (legacy.contains(from + ".custom-model-data")) {
                                merged.set(to + ".custom_model_data", legacy.getInt(from + ".custom-model-data"));
                            }
                        }
                    }
                }
                if (action != null && !action.isBlank() && !"NONE".equalsIgnoreCase(action)
                        && merged.contains(itemsPath) && !merged.contains(itemsPath + ".triggers.left")) {
                    merged.set(itemsPath + ".triggers.left.0", action);
                }
            }
        }
        return merged;
    }

    private void saveDefaultResource(File dir, String menuName) {
        File target = new File(dir, menuName + ".yml");
        if (target.exists()) {
            return;
        }
        String resourcePath = menuDir() + "/" + menuName + ".yml";
        try {
            plugin.saveResource(resourcePath, false);
        } catch (IllegalArgumentException ignored) {
            // jar 内无该资源（例如本地化目录被删），跳过
        }
    }

    private YamlConfiguration loadBuiltinResource(String path) {
        InputStream stream = plugin.getResource(path);
        if (stream == null) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    /**
     * config_version 合并升级：磁盘较旧 → 备份 .bak → 只补缺失 key → 写回并提升版本号。
     */
    private void upgradeIfNeeded(File file, FileConfiguration disk) {
        int diskVersion = disk.getInt("config_version", 0);
        String fileName = file.getName();
        YamlConfiguration builtin = loadBuiltinResource(menuDir() + "/" + fileName);
        if (builtin == null) {
            return;
        }
        int builtinVersion = builtin.getInt("config_version", CONFIG_VERSION);
        if (diskVersion >= builtinVersion) {
            return;
        }
        File backup = new File(file.getParentFile(), fileName + ".bak");
        if (backup.exists()) {
            backup.delete();
        }
        if (!file.renameTo(backup)) {
            plugin.getLogger().warning("菜单配置 " + fileName + " 升级前备份失败，已跳过自动升级。");
            return;
        }
        for (String key : builtin.getKeys(true)) {
            if ("config_version".equals(key)) {
                continue;
            }
            if (!disk.contains(key)) {
                disk.set(key, builtin.get(key));
            }
        }
        disk.set("config_version", builtinVersion);
        try {
            disk.save(new File(file.getParentFile(), fileName));
            plugin.getLogger().info("菜单配置 " + fileName + " 已升级到 v" + builtinVersion + "（旧文件备份为 " + backup.getName() + "）");
        } catch (Exception e) {
            plugin.getLogger().warning("保存升级后的菜单配置 " + fileName + " 失败: " + e.getMessage());
        }
    }

    // ─── 解析 ───

    private MenuConfig parseMenu(String menuName, FileConfiguration config) {
        MenuConfig menu = new MenuConfig(normalizeName(menuName));
        String base = menuName + ".";
        menu.setTitle(config.getString(base + "title", "菜单"));
        List<String> shape = config.getStringList(base + "shape");
        menu.setShape(shape.isEmpty() ? List.of() : shape);

        Map<String, MenuItem> items = new LinkedHashMap<>();
        ConfigurationSection itemsSection = config.getConfigurationSection(base + "items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                items.put(key, parseItem(base + "items." + key, config));
            }
        }
        // 兼容旧版 buttons 写法
        ConfigurationSection buttonsSection = config.getConfigurationSection(base + "buttons");
        if (buttonsSection != null) {
            for (String key : buttonsSection.getKeys(false)) {
                if (items.containsKey(key)) {
                    continue;
                }
                items.put(key, parseLegacyItem(base + "buttons." + key, config));
            }
        }
        // 兼容旧版 background 段：未定义 '#' 物品时按其生成装饰
        if (!items.containsKey("#")) {
            MenuItem bg = new MenuItem();
            bg.setMaterial(config.getString(base + "background.material",
                    config.getString("background.material", "GRAY_STAINED_GLASS_PANE")));
            bg.setName(config.getString(base + "background.name",
                    config.getString("background.name", " ")));
            items.put("#", bg);
        }
        menu.setItems(items);
        return menu;
    }

    private MenuItem parseItem(String path, FileConfiguration config) {
        MenuItem item = new MenuItem();
        item.setMaterial(config.getString(path + ".material", "STONE"));
        // 兼容 ALwarp 风格的插件物品键（plugin_item / craftengine / itemsadder / oraxen）
        for (String key : List.of("plugin_item", "craftengine", "itemsadder", "oraxen")) {
            String value = config.getString(path + "." + key, null);
            if (value != null && !value.isBlank()) {
                if (!value.contains(":")) {
                    value = switch (key) {
                        case "craftengine" -> "ce:" + value;
                        case "itemsadder" -> "ia:" + value;
                        case "oraxen" -> "oraxen:" + value;
                        default -> value;
                    };
                }
                item.setMaterial(value);
                break;
            }
        }
        item.setCustomModelData(config.getInt(path + ".custom_model_data",
                config.getInt(path + ".custom-model-data", 0)));
        item.setName(config.getString(path + ".name", ""));
        item.setLore(new ArrayList<>(config.getStringList(path + ".lore")));
        item.setDynamic(config.getBoolean(path + ".dynamic", false));

        ConfigurationSection states = config.getConfigurationSection(path + ".states");
        String statesKey = "states";
        if (states == null) {
            states = config.getConfigurationSection(path + ".state_overrides");
            statesKey = "state_overrides";
        }
        if (states != null) {
            for (String state : states.getKeys(false)) {
                MenuItem.StateStyle style = new MenuItem.StateStyle();
                String statePath = path + "." + statesKey + "." + state;
                style.setMaterial(config.getString(statePath + ".material"));
                style.setName(config.getString(statePath + ".name"));
                style.setLore(config.isList(statePath + ".lore") ? config.getStringList(statePath + ".lore") : null);
                if (config.contains(statePath + ".custom_model_data")) {
                    style.setCustomModelData(config.getInt(statePath + ".custom_model_data"));
                } else if (config.contains(statePath + ".custom-model-data")) {
                    style.setCustomModelData(config.getInt(statePath + ".custom-model-data"));
                }
                item.getStates().put(state, style);
            }
        }

        item.getTriggers().putAll(readTriggers(path + ".triggers", config));
        // 兼容旧版单值 action → left 触发
        String legacyAction = config.getString(path + ".action", "");
        if (legacyAction != null && !legacyAction.isBlank() && !"NONE".equalsIgnoreCase(legacyAction)
                && item.getTriggerActions("left").isEmpty()) {
            item.getTriggers().put("left", new ArrayList<>(List.of(legacyAction)));
        }
        return item;
    }

    private MenuItem parseLegacyItem(String path, FileConfiguration config) {
        MenuItem item = new MenuItem();
        item.setMaterial(config.getString(path + ".material", "STONE"));
        item.setCustomModelData(config.getInt(path + ".custom-model-data",
                config.getInt(path + ".custom_model_data", 0)));
        item.setName(config.getString(path + ".name", ""));
        item.setLore(new ArrayList<>(config.getStringList(path + ".lore")));
        item.setDynamic(config.getBoolean(path + ".dynamic", false));

        ConfigurationSection states = config.getConfigurationSection(path + ".state_overrides");
        if (states != null) {
            for (String state : states.getKeys(false)) {
                MenuItem.StateStyle style = new MenuItem.StateStyle();
                String statePath = path + ".state_overrides." + state;
                style.setMaterial(config.getString(statePath + ".material"));
                style.setName(config.getString(statePath + ".name"));
                style.setLore(config.getStringList(statePath + ".lore"));
                style.setCustomModelData(config.getInt(statePath + ".custom-model-data", 0));
                item.getStates().put(state, style);
            }
        }

        String action = config.getString(path + ".action", "");
        if (action != null && !action.isBlank() && !"NONE".equalsIgnoreCase(action)) {
            item.getTriggers().put("left", new ArrayList<>(List.of(action)));
        }
        return item;
    }

    private Map<String, List<String>> readTriggers(String path, FileConfiguration config) {
        Map<String, List<String>> triggers = new LinkedHashMap<>();
        for (String trigger : List.of("left", "right", "shift_left", "shift_right")) {
            List<String> actions = readStringList(config, path + "." + trigger);
            if (!actions.isEmpty()) {
                triggers.put(trigger, actions);
            }
        }
        return triggers;
    }

    private List<String> readStringList(FileConfiguration config, String path) {
        if (config.isList(path)) {
            List<String> result = new ArrayList<>();
            for (Object element : config.getList(path, List.of())) {
                if (element instanceof String s) {
                    result.add(s);
                } else if (element instanceof Map<?, ?> map && map.size() == 1) {
                    // 服主忘写引号时，"- sound: xxx" 会被 YAML 解析成单键 Map，这里还原成 "sound: xxx"
                    Map.Entry<?, ?> entry = map.entrySet().iterator().next();
                    result.add(entry.getKey() + ": " + entry.getValue());
                } else if (element != null) {
                    result.add(String.valueOf(element));
                }
            }
            return result;
        }
        String value = config.getString(path, null);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value);
    }

    // ─── 校验 ───

    private void validate(MenuConfig menu, List<String> errors) {
        List<String> shape = menu.getShape();
        if (shape.isEmpty()) {
            errors.add("缺少 shape 布局");
        }
        if (shape.size() > 6) {
            errors.add("shape 超过 6 行（当前 " + shape.size() + " 行）");
        }
        for (int i = 0; i < Math.min(shape.size(), 6); i++) {
            String row = shape.get(i);
            if (row.length() != 9) {
                errors.add("shape 第 " + (i + 1) + " 行不是 9 个字符（当前 " + row.length() + " 个）");
            }
        }
        for (String row : shape) {
            for (char c : row.toCharArray()) {
                if (c == '#' || c == ' ') {
                    continue;
                }
                if (!menu.getItems().containsKey(String.valueOf(c))) {
                    errors.add("shape 使用了字符 '" + c + "'，但 items 中没有定义");
                }
            }
        }
        for (Map.Entry<String, MenuItem> entry : menu.getItems().entrySet()) {
            MenuItem item = entry.getValue();
            for (Map.Entry<String, List<String>> trigger : item.getTriggers().entrySet()) {
                for (String action : trigger.getValue()) {
                    if (action == null || action.isBlank()) {
                        errors.add("items." + entry.getKey() + " 的 " + trigger.getKey() + " 触发动作存在空白动作串");
                    }
                }
            }
        }
    }

    private ConfigurationSection rawSection(String menuName) {
        for (File file : listMenuFiles()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = config.getConfigurationSection(menuName);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    private File[] listMenuFiles() {
        File[] files = menuDirFile().listFiles((d, name) -> name.endsWith(".yml"));
        return files == null ? new File[0] : files;
    }

    private static void copyExisting(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            Object value = from.get(key);
            if (value instanceof ConfigurationSection sub) {
                copyExisting(sub, to.createSection(key));
            } else {
                to.set(key, value);
            }
        }
    }

    public static String normalizeName(String menuName) {
        return menuName == null ? null : menuName.trim().replace('-', '_');
    }
}
