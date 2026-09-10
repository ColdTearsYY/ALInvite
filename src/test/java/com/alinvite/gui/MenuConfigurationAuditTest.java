package com.alinvite.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 菜单配置审计（不依赖 Bukkit 服务器）：直接读取 src/main/resources 下的菜单 YAML，
 * 校验 shape/items/triggers 结构，并保证 zh/en 两套目录不漂移。
 */
class MenuConfigurationAuditTest {

    private static final List<String> MENU_FILES =
        List.of("main_menu.yml", "veteran_menu.yml", "shop_menu.yml", "rebate_history.yml", "admin_rebate_history.yml");

    private File resource(String path) {
        // 兼容从项目根目录或模块目录运行测试
        File direct = new File("src/main/resources", path);
        if (direct.exists()) {
            return direct;
        }
        File module = new File("ALInvite/src/main/resources", path);
        if (module.exists()) {
            return module;
        }
        throw new AssertionError("找不到测试资源: " + path + " (cwd=" + new File(".").getAbsolutePath() + ")");
    }

    private YamlConfiguration load(File file) {
        return YamlConfiguration.loadConfiguration(file);
    }

    @Test
    void allMenuFilesExistInBothLocales() {
        for (String name : MENU_FILES) {
            YamlConfiguration zh = load(resource("menus/" + name));
            YamlConfiguration en = load(resource("menus_en/" + name));
            assertEquals(zh.getKeys(false), en.getKeys(false), "中英文菜单顶层键不一致: " + name);
        }
    }

    @Test
    void menuStructureIsValid() {
        List<String> errors = new ArrayList<>();
        for (String dir : List.of("menus", "menus_en")) {
            for (String name : MENU_FILES) {
                YamlConfiguration config = load(resource(dir + "/" + name));
                auditFile(dir + "/" + name, config, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new AssertionError("菜单配置审计失败:\n" + String.join("\n", errors));
        }
    }

    private void auditFile(String label, YamlConfiguration config, List<String> errors) {
        if (config.getInt("config_version", 0) < 1) {
            errors.add(label + ": 缺少 config_version");
        }
        for (String menuName : config.getKeys(false)) {
            if ("config_version".equals(menuName)) {
                continue;
            }
            String base = menuName + ".";
            List<String> shape = config.getStringList(base + "shape");
            if (shape.isEmpty()) {
                errors.add(label + " " + menuName + ": 缺少 shape");
                continue;
            }
            if (shape.size() > 6) {
                errors.add(label + " " + menuName + ": shape 超过 6 行");
            }
            for (int i = 0; i < shape.size(); i++) {
                if (shape.get(i).length() != 9) {
                    errors.add(label + " " + menuName + ": shape 第 " + (i + 1) + " 行不是 9 字符");
                }
            }

            ConfigurationSection items = config.getConfigurationSection(base + "items");
            if (items == null) {
                errors.add(label + " " + menuName + ": 缺少 items");
                continue;
            }
            for (String row : shape) {
                for (char c : row.toCharArray()) {
                    if (c == '#' || c == ' ') {
                        continue;
                    }
                    if (!items.contains(String.valueOf(c))) {
                        errors.add(label + " " + menuName + ": 字符 '" + c + "' 未在 items 定义");
                    }
                }
            }

            for (String itemKey : items.getKeys(false)) {
                String itemPath = base + "items." + itemKey;
                String material = config.getString(itemPath + ".material", "");
                if (material.isBlank()) {
                    errors.add(label + " " + menuName + " items." + itemKey + ": 缺少 material");
                }
                for (String trigger : List.of("left", "right", "shift_left", "shift_right")) {
                    for (String action : config.getStringList(itemPath + ".triggers." + trigger)) {
                        if (action.isBlank()) {
                            errors.add(label + " " + menuName + " items." + itemKey
                                + ": " + trigger + " 存在空白动作");
                        }
                    }
                }
            }
        }
    }

    @Test
    void localeMenuDirsMatch() {
        List<String> zhFiles = Arrays.asList(Objects.requireNonNull(resource("menus").list()));
        List<String> enFiles = Arrays.asList(Objects.requireNonNull(resource("menus_en").list()));
        assertEquals(new ArrayList<>(zhFiles).stream().sorted().toList(),
                new ArrayList<>(enFiles).stream().sorted().toList(),
                "menus/ 与 menus_en/ 文件列表不一致");
    }

    @Test
    void multilineMessagesStayAsLists() {
        // 多行文案必须保持列表写法（一行一条）：代码端按 \n 拼接后发送
        for (String dir : List.of("languages/zh_cn.yml", "languages/en_us.yml")) {
            YamlConfiguration lang = load(resource(dir));
            for (String key : List.of("commands.stats", "commands.help", "commands.admin.help")) {
                org.junit.jupiter.api.Assertions.assertTrue(lang.isList(key),
                        dir + " 的 " + key + " 应保持列表写法（一行一条）");
                org.junit.jupiter.api.Assertions.assertFalse(lang.getStringList(key).isEmpty(),
                        dir + " 的 " + key + " 列表为空");
            }
        }
    }
}
