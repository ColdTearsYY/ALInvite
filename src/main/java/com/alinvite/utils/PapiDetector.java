package com.alinvite.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PlaceholderAPI 占位符检测与扫描工具类
 * 用于检测其他插件注册的 PAPI 变量，以及扫描菜单/消息中的占位符使用情况
 */
public class PapiDetector {

    /**
     * 占位符扫描结果条目
     */
    public static class PlaceholderEntry {
        public final String placeholder;      // 完整占位符，如 %luckperms_prefix%
        public final String identifier;        // 扩展标识符，如 luckperms
        public final String source;            // 来源分类: "ALInvite内置" 或 "第三方"
        public final String expansionName;     // 扩展显示名称

        public PlaceholderEntry(String placeholder, String identifier, String source, String expansionName) {
            this.placeholder = placeholder;
            this.identifier = identifier;
            this.source = source;
            this.expansionName = expansionName;
        }
    }

    /**
     * 扫描结果汇总
     */
    public static class ScanResult {
        public final String sourceName;                          // 扫描来源名称
        public final List<PlaceholderEntry> alinvitePlaceholders;  // ALInvite 内置占位符
        public final List<PlaceholderEntry> thirdPartyPlaceholders; // 第三方占位符

        public ScanResult(String sourceName) {
            this.sourceName = sourceName;
            this.alinvitePlaceholders = new ArrayList<>();
            this.thirdPartyPlaceholders = new ArrayList<>();
        }

        public int getTotalCount() {
            return alinvitePlaceholders.size() + thirdPartyPlaceholders.size();
        }

        public int getThirdPartyCount() {
            return thirdPartyPlaceholders.size();
        }

        public boolean hasThirdParty() {
            return !thirdPartyPlaceholders.isEmpty();
        }
    }

    private static final Set<String> ALINVITE_IDENTIFIERS = Set.of("alinvite");

    /**
     * 检查 PlaceholderAPI 是否可用
     */
    public static boolean isPapiAvailable() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /**
     * 获取所有已注册的 PAPI 扩展标识符
     * @return 扩展标识符集合
     */
    public static Set<String> getRegisteredIdentifiers() {
        if (!isPapiAvailable()) {
            return Collections.emptySet();
        }
        try {
            return PlaceholderAPI.getRegisteredIdentifiers();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /**
     * 获取所有第三方 PAPI 扩展（排除 ALInvite 自身）
     * @return 第三方扩展标识符集合
     */
    public static Set<String> getThirdPartyIdentifiers() {
        return getRegisteredIdentifiers().stream()
                .filter(id -> !ALINVITE_IDENTIFIERS.contains(id.toLowerCase()))
                .collect(Collectors.toSet());
    }

    /**
     * 获取已注册的扩展信息列表
     * @return 扩展信息映射 (identifier -> description)
     */
    public static Map<String, String> getExpansionInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        if (!isPapiAvailable()) {
            return info;
        }

        // ALInvite 内置占位符
        info.put("alinvite", "ALInvite 内置占位符 (code, total, gift_name, bind_status, 等)");

        // 第三方扩展
        for (String id : getThirdPartyIdentifiers()) {
            String desc = getExpansionDescription(id);
            info.put(id, desc);
        }

        return info;
    }

    /**
     * 获取扩展的描述信息
     */
    private static String getExpansionDescription(String identifier) {
        try {
            // PAPI 不直接提供通过标识符获取 Expansion 实例的公共 API
            // 使用 PlaceholderAPI 自身的方法来获取信息
            Set<String> placeholders = PlaceholderAPI.getRegisteredIdentifiers();
            if (placeholders.contains(identifier)) {
                return identifier + " 扩展 (已注册)";
            }
            return identifier + " (状态未知)";
        } catch (Exception e) {
            return identifier + " (获取信息失败)";
        }
    }

    /**
     * 扫描文本中所有 PAPI 占位符
     * @param text 要扫描的文本
     * @return 占位符列表（去重），分类为内置/第三方
     */
    public static List<PlaceholderEntry> scanText(String text) {
        List<PlaceholderEntry> entries = new ArrayList<>();
        if (text == null || text.isEmpty() || !isPapiAvailable()) {
            return entries;
        }

        Set<String> seen = new HashSet<>();
        Pattern pattern = PlaceholderAPI.getPlaceholderPattern();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String fullMatch = matcher.group(); // 完整占位符如 %alinvite_code%
            if (!seen.add(fullMatch.toLowerCase())) {
                continue; // 去重
            }

            // 提取标识符（% 中间的部分，去掉 % 符号）
            String inner = fullMatch.substring(1, fullMatch.length() - 1);
            String identifier = inner.contains("_") ? inner.substring(0, inner.indexOf('_')) : inner;

            String source;
            if (ALINVITE_IDENTIFIERS.contains(identifier.toLowerCase())) {
                source = "ALInvite内置";
            } else {
                source = "第三方";
            }

            entries.add(new PlaceholderEntry(fullMatch, identifier, source, identifier + " 扩展"));
        }

        return entries;
    }

    /**
     * 扫描文本列表中的所有 PAPI 占位符
     * @param texts 文本列表
     * @return 占位符列表（去重）
     */
    public static List<PlaceholderEntry> scanTexts(List<String> texts) {
        List<PlaceholderEntry> allEntries = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String text : texts) {
            for (PlaceholderEntry entry : scanText(text)) {
                if (seen.add(entry.placeholder.toLowerCase())) {
                    allEntries.add(entry);
                }
            }
        }

        return allEntries;
    }

    /**
     * 收集配置中所有文本用于扫描
     */
    private static List<String> collectConfigTexts(org.bukkit.configuration.ConfigurationSection section) {
        List<String> texts = new ArrayList<>();
        if (section == null) return texts;

        for (String key : section.getKeys(true)) {
            Object value = section.get(key);
            if (value instanceof String str) {
                texts.add(str);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String str) {
                        texts.add(str);
                    }
                }
            }
        }
        return texts;
    }

    /**
     * 扫描菜单中的所有 PAPI 占位符
     * @param menuSection 菜单配置节 (menus.yml中的 main_menu/veteran_menu/shop_menu)
     * @param menuName 菜单名称（用于结果标识）
     * @return 扫描结果
     */
    public static ScanResult scanMenu(org.bukkit.configuration.ConfigurationSection menuSection, String menuName) {
        ScanResult result = new ScanResult(menuName);
        if (menuSection == null) return result;

        List<String> texts = new ArrayList<>();
        // 收集标题
        String title = menuSection.getString("title");
        if (title != null) texts.add(title);

        // 收集按钮配置
        org.bukkit.configuration.ConfigurationSection buttons = menuSection.getConfigurationSection("buttons");
        if (buttons != null) {
            texts.addAll(collectConfigTexts(buttons));
        }

        // 扫描
        List<PlaceholderEntry> entries = scanTexts(texts);
        for (PlaceholderEntry entry : entries) {
            if ("ALInvite内置".equals(entry.source)) {
                result.alinvitePlaceholders.add(entry);
            } else {
                result.thirdPartyPlaceholders.add(entry);
            }
        }

        return result;
    }

    /**
     * 扫描所有菜单配置
     * @param menusConfig 完整的 menus.yml 配置
     * @return 各菜单的扫描结果
     */
    public static Map<String, ScanResult> scanAllMenus(org.bukkit.configuration.ConfigurationSection menusConfig) {
        Map<String, ScanResult> results = new LinkedHashMap<>();

        String[] menuNames = {"main_menu", "veteran_menu", "shop_menu"};
        for (String name : menuNames) {
            org.bukkit.configuration.ConfigurationSection section = menusConfig.getConfigurationSection(name);
            if (section != null) {
                results.put(name, scanMenu(section, name));
            }
        }

        return results;
    }

    /**
     * 测试指定占位符对玩家的解析结果
     * @param placeholder 占位符（如 %luckperms_prefix%）
     * @param playerName 玩家名称
     * @return 解析结果字符串，若失败则返回错误信息
     */
    public static String testPlaceholder(String placeholder, String playerName) {
        if (!isPapiAvailable()) {
            return "§cPlaceholderAPI 未安装";
        }

        // 确保占位符有 % 包裹
        String fullPlaceholder = placeholder;
        if (!fullPlaceholder.startsWith("%")) fullPlaceholder = "%" + fullPlaceholder;
        if (!fullPlaceholder.endsWith("%")) fullPlaceholder = fullPlaceholder + "%";

        org.bukkit.entity.Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            // 尝试用离线玩家
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            if (offlinePlayer == null || !offlinePlayer.hasPlayedBefore()) {
                return "§c玩家 " + playerName + " 不存在或未在线";
            }
            // 对于离线玩家，使用 setPlaceholders 需要在线玩家，所以返回提示
            return "§c玩家 " + playerName + " 不在线，无法测试占位符（需要在线玩家）";
        }

        try {
            String result = PlaceholderAPI.setPlaceholders(player, fullPlaceholder);
            if (result.equals(fullPlaceholder)) {
                return "§e未解析: " + result + " §7(占位符可能不存在或返回原值)";
            }
            return "§a解析结果: " + result;
        } catch (Exception e) {
            return "§c解析失败: " + e.getMessage();
        }
    }
}
