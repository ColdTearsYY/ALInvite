package com.alinvite.gui;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.manager.GiftManager;
import com.alinvite.manager.MilestoneManager;
import com.alinvite.utils.PlaceholderResolver;
import com.alinvite.utils.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class MenuManager {

    private final ALInvite plugin;
    private final Map<String, MenuConfig> menus;
    private final PlaceholderResolver placeholderResolver;

    public MenuManager(ALInvite plugin) {
        this.plugin = plugin;
        this.menus = new HashMap<>();
        this.placeholderResolver = new PlaceholderResolver(plugin);
        loadMenus();
    }

    public PlaceholderResolver getPlaceholderResolver() {
        return placeholderResolver;
    }

    private void loadMenus() {
        var menusSection = plugin.getConfigManager().getMenusConfig().getConfigurationSection("main_menu");
        if (menusSection != null) {
            menus.put("main_menu", loadMenuConfig("main_menu"));
        }

        menusSection = plugin.getConfigManager().getMenusConfig().getConfigurationSection("veteran_menu");
        if (menusSection != null) {
            menus.put("veteran_menu", loadMenuConfig("veteran_menu"));
        }

        menusSection = plugin.getConfigManager().getMenusConfig().getConfigurationSection("shop_menu");
        if (menusSection != null) {
            menus.put("shop_menu", loadMenuConfig("shop_menu"));
        }
    }

    private MenuConfig loadMenuConfig(String menuName) {
        MenuConfig config = new MenuConfig();
        config.title = plugin.getConfigManager().getMenusConfig().getString(menuName + ".title", "菜单");
        config.pages = plugin.getConfigManager().getMenusConfig().getInt(menuName + ".pages", 1); // 默认为1页
        config.shape = plugin.getConfigManager().getMenusConfig().getStringList(menuName + ".shape");
        config.shape2 = plugin.getConfigManager().getMenusConfig().getStringList(menuName + ".shape2");
        config.buttons = new HashMap<>();

        var buttonsSection = plugin.getConfigManager().getMenusConfig().getConfigurationSection(menuName + ".buttons");
        if (buttonsSection != null) {
            for (String key : buttonsSection.getKeys(false)) {
                if (key.equals("background")) continue;
                ButtonConfig btn = new ButtonConfig();
                btn.material = plugin.getConfigManager().getMenusConfig().getString(menuName + ".buttons." + key + ".material", "STONE");
                btn.name = plugin.getConfigManager().getMenusConfig().getString(menuName + ".buttons." + key + ".name", "");
                btn.lore = plugin.getConfigManager().getMenusConfig().getStringList(menuName + ".buttons." + key + ".lore");
                btn.action = plugin.getConfigManager().getMenusConfig().getString(menuName + ".buttons." + key + ".action", "NONE");
                btn.isDynamic = plugin.getConfigManager().getMenusConfig().getBoolean(menuName + ".buttons." + key + ".dynamic", false);
                btn.customModelData = plugin.getConfigManager().getMenusConfig().getInt(menuName + ".buttons." + key + ".custom-model-data", 0);

                var stateOverrides = plugin.getConfigManager().getMenusConfig().getConfigurationSection(menuName + ".buttons." + key + ".state_overrides");
                if (stateOverrides != null) {
                    btn.stateOverrides = new HashMap<>();
                    for (String state : stateOverrides.getKeys(false)) {
                        ButtonConfig stateBtn = new ButtonConfig();
                        stateBtn.material = plugin.getConfigManager().getMenusConfig().getString(menuName + ".buttons." + key + ".state_overrides." + state + ".material", "STONE");
                        stateBtn.name = plugin.getConfigManager().getMenusConfig().getString(menuName + ".buttons." + key + ".state_overrides." + state + ".name", "");
                        stateBtn.lore = plugin.getConfigManager().getMenusConfig().getStringList(menuName + ".buttons." + key + ".state_overrides." + state + ".lore");
                        stateBtn.action = plugin.getConfigManager().getMenusConfig().getString(menuName + ".buttons." + key + ".state_overrides." + state + ".action", "NONE");
                        stateBtn.customModelData = plugin.getConfigManager().getMenusConfig().getInt(menuName + ".buttons." + key + ".state_overrides." + state + ".custom-model-data", 0);
                        btn.stateOverrides.put(state, stateBtn);
                    }
                }

                config.buttons.put(key, btn);
            }
        }

        config.backgroundMaterial = plugin.getConfigManager().getMenusConfig().getString("background.material", "BLACK_STAINED_GLASS_PANE");
        config.backgroundName = plugin.getConfigManager().getMenusConfig().getString("background.name", " ");
        config.backgroundLore = new ArrayList<>();

        return config;
    }

    public void openMainMenu(Player player) {
        if (isDebug()) plugin.getLogger().info("[DEBUG] openMainMenu called for player: " + player.getName());
        openMenu(player, "main_menu", "main");
    }

    public void openVeteranMenu(Player player) {
        openMenu(player, "veteran_menu", "veteran");
    }

    public void openShopMenu(Player player) {
        openMenu(player, "shop_menu", "shop");
    }

    private void openMenu(Player player, String menuName, String menuKey) {
        if (isDebug()) plugin.getLogger().info("[DEBUG] openMenu called - player: " + player.getName() + ", menuName: " + menuName + ", menuKey: " + menuKey);
        MenuConfig config = menus.get(menuName);
        if (config == null) {
            player.sendMessage("菜单配置不存在");
            return;
        }

        // 获取现有 session（如果有）
        MenuSession existingSession = MenuSessionManager.getInstance().getSession(player);
        
        // 对于 veteran 和 shop 菜单，尝试保持页码，但只有在菜单类型相同时才复用 page
        int page = 0;
        if ((menuKey.equals("veteran") || menuKey.equals("shop")) && 
            existingSession != null && existingSession.getMenuType().equals(menuKey)) {
            page = existingSession.getPage();
            if (isDebug()) plugin.getLogger().info("[DEBUG] Reusing page: " + page + " for same menu type");
        }
        
        // 验证页码有效性
        page = validatePage(page, menuKey);

        // 选择使用哪个形状（根据页码）
        List<String> currentShape;
        if (menuKey.equals("veteran") || menuKey.equals("shop")) {
            currentShape = page == 0 ? config.shape : (config.shape2 != null && !config.shape2.isEmpty() ? config.shape2 : config.shape);
        } else {
            currentShape = config.shape;
        }
        
        // 根据当前形状创建正确大小的库存
        Map<Integer, String> slotActions = buildSlotActions(config, currentShape);
        int menuSize = currentShape.size() * 9;
        // 对标题进行占位符解析（支持第三方 PAPI 变量）
        String resolvedTitle = placeholderResolver.applyPlaceholders(config.title, player);
        Inventory inventory = Bukkit.createInventory(new MenuHolder(config.title), menuSize, ConfigManager.colorize(resolvedTitle));
        
        // 总是创建新的菜单会话（确保 slotActions 是正确的）
        MenuSession session = new MenuSession(player, menuKey, config, slotActions, menuSize);
        session.setPage(page); // 设置页码
        MenuSessionManager.getInstance().createSession(player, menuKey, session);
        if (isDebug()) plugin.getLogger().info("[DEBUG] Created new session with " + slotActions.size() + " slot actions");
        
        if (menuKey.equals("veteran")) {
            createVeteranInventory(player, config, inventory, slotActions);
        } else if (menuKey.equals("shop")) {
            createShopInventory(player, config, inventory, slotActions);
        } else if (menuKey.equals("main")) {
            createMainMenuInventoryAsync(player, inventory, config, slotActions);
        }
    }
    
    private boolean isDebug() {
        return plugin.getConfigManager().getConfig().getBoolean("debug", false);
    }

    private int validatePage(int page, String menuKey) {
        int maxPage = 0;
        if ("veteran".equals(menuKey)) {
            int milestonesPerPage = 14;
            int totalMilestones = plugin.getMilestoneManager().getMilestones().size();
            maxPage = Math.max(0, (int) Math.ceil((double) totalMilestones / milestonesPerPage) - 1);
        } else if ("shop".equals(menuKey)) {
            int giftsPerPage = 7;
            int totalGifts = plugin.getGiftManager().getAllGifts().size();
            maxPage = Math.max(0, (int) Math.ceil((double) totalGifts / giftsPerPage) - 1);
        }
        
        // 确保页码在有效范围内
        int validatedPage = Math.max(0, Math.min(page, maxPage));
        if (validatedPage != page && isDebug()) {
            plugin.getLogger().info("[DEBUG] Page validation: " + page + " -> " + validatedPage + " (max: " + maxPage + ")");
        }
        return validatedPage;
    }

    private Map<Integer, String> buildSlotActions(MenuConfig config, List<String> shape) {
        Map<Integer, String> slotActions = new HashMap<>();
        int slot = 0;
        for (String row : shape) {
            for (char c : row.toCharArray()) {
                if (c != '#') {
                    ButtonConfig btn = config.buttons.get(String.valueOf(c));
                    if (btn != null) {
                        slotActions.put(slot, btn.action);
                    }
                }
                slot++;
            }
        }
        return slotActions;
    }

    private void createMainMenuInventoryAsync(Player player, Inventory inventory, MenuConfig config, Map<Integer, String> slotActions) {
        if (isDebug()) plugin.getLogger().info("[DEBUG] createMainMenuInventoryAsync called for player: " + player.getName());
        String veteranPerm = plugin.getConfigManager().getConfig()
            .getString("invite_code.veteran_permission", "alinvite.veteran");
        boolean hasPermission = player.hasPermission(veteranPerm);

        plugin.getDatabaseManager().getPlayerData(player.getUniqueId()).thenAccept(data -> {
            final String existingCode = data != null ? data.inviteCode : null;

            if (hasPermission && existingCode == null) {
                plugin.getInviteManager().generateInviteCode(player.getUniqueId()).thenAccept(newCode -> {
                    showMainMenuWithData(player, inventory, config, slotActions, hasPermission, newCode);
                });
            } else {
                showMainMenuWithData(player, inventory, config, slotActions, hasPermission, existingCode);
            }
        });
    }

    private void showMainMenuWithData(Player player, Inventory inventory, MenuConfig config, Map<Integer, String> slotActions, boolean hasPermission, String inviteCode) {
        final String codeStatus = hasPermission ? (inviteCode != null ? inviteCode : "生成中...") : "未解锁";

        plugin.getDatabaseManager().getInviter(player.getUniqueId()).thenAccept(inviterUuid -> {
            String bindStatus = "未绑定";
            String inviterName = "未绑定";
            if (inviterUuid != null) {
                plugin.getDatabaseManager().getPlayerData(inviterUuid).thenAccept(inviterData -> {
                    String finalInviterName;
                    String finalBindStatus;
                    if (inviterData != null) {
                        Player onlineInviter = plugin.getServer().getPlayer(inviterUuid);
                        finalInviterName = onlineInviter != null && onlineInviter.isOnline() ? onlineInviter.getName() : inviterData.inviteCode;
                        finalBindStatus = "已绑定";
                    } else {
                        finalInviterName = "未绑定";
                        finalBindStatus = "未绑定";
                    }
                    fillMainMenuInventory(player, inventory, config, slotActions, finalBindStatus, finalInviterName, codeStatus);
                });
            } else {
                fillMainMenuInventory(player, inventory, config, slotActions, bindStatus, inviterName, codeStatus);
            }
        });
    }

    private void fillMainMenuInventory(Player player, Inventory inventory, MenuConfig config, Map<Integer, String> slotActions, String bindStatus, String inviterName, String inviteCode) {
        int slot = 0;
        for (String row : config.shape) {
            for (char c : row.toCharArray()) {
                if (c == '#') {
                    inventory.setItem(slot, createBackgroundItem(config));
                } else {
                    ButtonConfig btn = config.buttons.get(String.valueOf(c));
                    if (btn != null) {
                        inventory.setItem(slot, createMainButtonItem(player, btn, bindStatus, inviterName, inviteCode));
                    }
                }
                slot++;
            }
        }
        
        SchedulerUtils.runTask(plugin, () -> {
            player.openInventory(inventory);
        });
    }

    private ItemStack createMainButtonItem(Player player, ButtonConfig btn, String bindStatus, String inviterName, String inviteCode) {
        String displayCode = inviteCode != null ? inviteCode : "无";
        ItemStack item = new ItemStack(Material.valueOf(btn.material));
        ItemMeta meta = item.getItemMeta();
        
        // 先应用占位符替换，再替换其他变量
        String name = placeholderResolver.applyPlaceholders(btn.name, player)
                .replace("{bind_status}", bindStatus)
                .replace("{inviter_name}", inviterName)
                .replace("{invite_code}", displayCode);
        name = ConfigManager.colorize(name);
        
        List<String> lore = btn.lore.stream()
                .map(s -> {
                    // 先应用占位符替换，再替换其他变量
                    String processed = placeholderResolver.applyPlaceholders(s, player)
                            .replace("{bind_status}", bindStatus)
                            .replace("{inviter_name}", inviterName)
                            .replace("{invite_code}", displayCode);
                    return ConfigManager.colorize(processed);
                })
                .toList();
        
        meta.setDisplayName(name);
        meta.setLore(lore);
        if (btn.customModelData > 0) {
            meta.setCustomModelData(btn.customModelData);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private Inventory createInventory(Player player, MenuConfig config) {
        int size = config.shape.size() * 9;
        String title = ConfigManager.colorize(config.title);
        Inventory inventory = Bukkit.createInventory(new MenuHolder(config.title), size, title);

        int slot = 0;
        for (String row : config.shape) {
            for (char c : row.toCharArray()) {
                if (c == '#') {
                    inventory.setItem(slot, createBackgroundItem(config));
                } else {
                    ButtonConfig btn = config.buttons.get(String.valueOf(c));
                    if (btn != null) {
                        inventory.setItem(slot, createButtonItem(player, btn));
                    }
                }
                slot++;
            }
        }

        return inventory;
    }

    private Map<Integer, String> buildSlotActions(MenuConfig config) {
        Map<Integer, String> slotActions = new HashMap<>();
        int slot = 0;
        for (String row : config.shape) {
            for (char c : row.toCharArray()) {
                if (c != '#') {
                    ButtonConfig btn = config.buttons.get(String.valueOf(c));
                    if (btn != null) {
                        slotActions.put(slot, btn.action);
                    }
                }
                slot++;
            }
        }
        return slotActions;
    }

    private void createVeteranInventory(Player player, MenuConfig config, Inventory inventory, Map<Integer, String> slotActions) {
        // 创建新的 slotActions 确保不会修改传入的原始 map
        Map<Integer, String> localSlotActions = new HashMap<>(slotActions);
        
        plugin.getDatabaseManager().getClaimedMilestones(player.getUniqueId()).thenAccept(claimedJson -> {
            Set<String> claimed = parseJsonArray(claimedJson);
            plugin.getDatabaseManager().getPlayerData(player.getUniqueId()).thenAccept(data -> {
                plugin.getDatabaseManager().getGiftId(player.getUniqueId()).thenAccept(currentGiftId -> {
                    int total = data != null ? data.totalInvites : 0;
                    String inviteCode = data != null ? data.inviteCode : null;
                    Map<Integer, MilestoneManager.Milestone> milestones = plugin.getMilestoneManager().getMilestones();

                    String defaultGiftId = plugin.getConfigManager().getConfig()
                        .getString("new_player_reward.default_gift_id", "default");
                    String finalGiftId = currentGiftId != null ? currentGiftId : defaultGiftId;
                    GiftManager.GiftConfig currentGift = plugin.getGiftManager().getGift(finalGiftId);

                    // 获取当前页码
                    MenuSession session = MenuSessionManager.getInstance().getSession(player);
                    int page = session != null ? session.getPage() : 0;

                    // 选择使用哪个形状（根据页码）
                    List<String> currentShape = page == 0 ? config.shape : (config.shape2 != null && !config.shape2.isEmpty() ? config.shape2 : config.shape);

                    // 收集所有 M 位置的槽位
                    List<Integer> milestoneSlots = new ArrayList<>();
                    int shapeSlot = 0;
                    for (String row : currentShape) {
                        for (char c : row.toCharArray()) {
                            if (c == 'M') {
                                milestoneSlots.add(shapeSlot);
                            }
                            shapeSlot++;
                        }
                    }
                    
                    // 计算每页显示的里程碑数量（基于布局中的 M 位置数量）
                    int milestonesPerPage = milestoneSlots.size();
                    List<Map.Entry<Integer, MilestoneManager.Milestone>> milestoneList = new ArrayList<>(milestones.entrySet());
                    int startIndex = page * milestonesPerPage;
                    int endIndex = Math.min(startIndex + milestonesPerPage, milestoneList.size());
                    List<Map.Entry<Integer, MilestoneManager.Milestone>> currentPageMilestones = milestoneList.subList(startIndex, endIndex);
                    
                    // 计算总页数
                    int totalPages = (int) Math.ceil((double) milestoneList.size() / milestonesPerPage);
                    // 确保总页数至少为1
                    totalPages = Math.max(totalPages, 1);

                    // 放置里程碑物品
                    for (int i = 0; i < currentPageMilestones.size(); i++) {
                        if (i >= milestoneSlots.size()) {
                            break; // 没有足够的 M 位置
                        }
                        
                        Map.Entry<Integer, MilestoneManager.Milestone> entry = currentPageMilestones.get(i);
                        int required = entry.getKey();
                        MilestoneManager.Milestone milestone = entry.getValue();
                        int milestoneSlot = milestoneSlots.get(i);

                        String state;
                        if (claimed.contains(String.valueOf(required))) {
                            state = "claimed";
                        } else if (total >= required) {
                            state = "available";
                        } else {
                            state = "locked";
                        }

                        ButtonConfig btn = config.buttons.get("M");
                        ButtonConfig stateBtn = btn != null && btn.stateOverrides != null ?
                            btn.stateOverrides.get(state) : null;

                        String materialStr = stateBtn != null ? stateBtn.material : btn.material;
                        String name = stateBtn != null && stateBtn.name != null ? stateBtn.name : btn.name;
                        List<String> lore = stateBtn != null && stateBtn.lore != null ? stateBtn.lore : btn.lore;

                        name = placeholderResolver.applyPlaceholders(name, player);
                        name = name.replace("{milestone_name}", milestone.name);

                        List<String> milestoneRewardLore = formatMilestoneRewardsLore(milestone);
                        final int finalTotal = total;
                        List<String> finalLore = new ArrayList<>();
                        for (String loreLine : lore) {
                            String processed = placeholderResolver.applyPlaceholders(loreLine, player);
                            processed = processed.replace("{milestone_name}", milestone.name)
                                .replace("{current}", String.valueOf(finalTotal))
                                .replace("{required}", String.valueOf(required))
                                .replace("{remaining}", String.valueOf(Math.max(0, required - finalTotal)))
                                .replace("{status}", state)
                                .replace("{status_text}", getStatusText(state));

                            if (processed.contains("{reward_lore}")) {
                                for (String rewardLine : milestoneRewardLore) {
                                    finalLore.add(processed.replace("{reward_lore}", rewardLine));
                                }
                            } else if (processed.contains("{reward_lore_")) {
                                boolean replaced = false;
                                for (int j = 0; j < milestoneRewardLore.size(); j++) {
                                    String placeholder = "{reward_lore_" + j + "}";
                                    if (processed.contains(placeholder)) {
                                        finalLore.add(processed.replace(placeholder, milestoneRewardLore.get(j)));
                                        replaced = true;
                                        break;
                                    }
                                }
                                if (!replaced) {
                                    continue;
                                }
                            } else {
                                finalLore.add(processed);
                            }
                        }
                        lore = finalLore;

                        Material mat;
                        try {
                            mat = Material.valueOf(materialStr);
                        } catch (IllegalArgumentException e) {
                            mat = Material.STONE;
                        }
                        ItemStack item = new ItemStack(mat);
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setDisplayName(ConfigManager.colorize(name));
                            meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
                            int cmd = stateBtn != null ? stateBtn.customModelData : btn.customModelData;
                            if (cmd > 0) {
                                meta.setCustomModelData(cmd);
                            }
                            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                            item.setItemMeta(meta);
                        }

                        if (milestoneSlot < inventory.getSize()) {
                            inventory.setItem(milestoneSlot, item);
                        }
                        localSlotActions.put(milestoneSlot, "CLAIM_MILESTONE");
                    }

                    int layoutSlot = 0;
                    for (String row : currentShape) {
                        for (char c : row.toCharArray()) {
                            if (c == '#') {
                                if (inventory.getItem(layoutSlot) == null) {
                                    inventory.setItem(layoutSlot, createBackgroundItem(config));
                                }
                            } else if (c == 'C') {
                                ButtonConfig closeBtn = config.buttons.get("C");
                                if (closeBtn != null) {
                                    inventory.setItem(layoutSlot, createButtonItem(player, closeBtn));
                                    localSlotActions.put(layoutSlot, "CLOSE");
                                }
                            } else if (c == 'G') {
                                inventory.setItem(layoutSlot, createGiftPreviewItem(player, config, currentGift));
                                localSlotActions.put(layoutSlot, "OPEN_SHOP");
                            } else if (c == 'P') { // 上一页按钮
                                ButtonConfig prevBtn = config.buttons.get("P");
                                if (prevBtn != null) {
                                    ItemStack item = createButtonItem(player, prevBtn);
                                    ItemMeta meta = item.getItemMeta();
                                    if (meta != null) {
                                        // 获取原有的 lore（来自配置，已通过 createButtonItem 解析 PAPI）
                                        List<String> existingLore = meta.getLore();
                                        List<String> lore = new ArrayList<>();
                                        lore.add(placeholderResolver.applyPlaceholders("&7当前页: " + (page + 1) + "/" + totalPages, player));
                                        lore.add(placeholderResolver.applyPlaceholders("&7查看上一页里程碑", player));

                                        // 如果是第一页，禁用上一页按钮
                                        if (page == 0) {
                                            meta.setDisplayName(ConfigManager.colorize(placeholderResolver.applyPlaceholders(prevBtn.name, player)));
                                            lore.clear();
                                            lore.add(placeholderResolver.applyPlaceholders("&7当前页: 1/" + totalPages, player));
                                            lore.add(placeholderResolver.applyPlaceholders("&7已经是第一页", player));
                                        }
                                        meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
                                        item.setItemMeta(meta);
                                    }
                                    inventory.setItem(layoutSlot, item);
                                    localSlotActions.put(layoutSlot, "PREV_PAGE");
                                }
                            } else if (c == 'N') { // 下一页按钮
                                ButtonConfig nextBtn = config.buttons.get("N");
                                if (nextBtn != null) {
                                    ItemStack item = createButtonItem(player, nextBtn);
                                    ItemMeta meta = item.getItemMeta();
                                    if (meta != null) {
                                        // 设置页码信息
                                        List<String> lore = new ArrayList<>();
                                        lore.add(placeholderResolver.applyPlaceholders("&7当前页: " + (page + 1) + "/" + totalPages, player));
                                        lore.add(placeholderResolver.applyPlaceholders("&7查看下一页里程碑", player));

                                        // 如果是最后一页，禁用下一页按钮
                                        if (endIndex >= milestoneList.size()) {
                                            meta.setDisplayName(ConfigManager.colorize(placeholderResolver.applyPlaceholders(nextBtn.name, player)));
                                            lore.clear();
                                            lore.add(placeholderResolver.applyPlaceholders("&7当前页: " + totalPages + "/" + totalPages, player));
                                            lore.add(placeholderResolver.applyPlaceholders("&7已经是最后一页", player));
                                        }
                                        meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
                                        item.setItemMeta(meta);
                                    }
                                    inventory.setItem(layoutSlot, item);
                                    localSlotActions.put(layoutSlot, "NEXT_PAGE");
                                }
                            } else if (c == 'B') {
                                ButtonConfig btn = config.buttons.get("B");
                                if (btn != null) {
                                    inventory.setItem(layoutSlot, createButtonItem(player, btn));
                                    localSlotActions.put(layoutSlot, "OPEN_SHOP");
                                }
                            } else if (c == 'A') {
                                ButtonConfig btn = config.buttons.get("A");
                                if (btn != null) {
                                    inventory.setItem(layoutSlot, createButtonItem(player, btn));
                                    localSlotActions.put(layoutSlot, "OPEN_MAIN");
                                }
                            } else if (c != 'M') {
                                ButtonConfig btn = config.buttons.get(String.valueOf(c));
                                if (btn != null) {
                                    inventory.setItem(layoutSlot, createButtonItem(player, btn));
                                }
                            }
                            layoutSlot++;
                        }
                    }

                    final Inventory inv = inventory;
                    final Map<Integer, String> finalSlotActions = localSlotActions;
                    final int finalPage = page;
                    
                    // 更新现有的 session
                    MenuSession existingSession = MenuSessionManager.getInstance().getSession(player);
                    if (existingSession != null) {
                        existingSession.updateSlotActions(finalSlotActions);
                        existingSession.setPage(finalPage);
                    }
                    
                    SchedulerUtils.runTask(plugin, () -> {
                        player.openInventory(inv);
                    });
                });
            });
        });
    }

    private ItemStack createGiftPreviewItem(Player player, MenuConfig config, GiftManager.GiftConfig gift) {
        ButtonConfig btn = config.buttons.get("G");
        String materialStr = btn != null ? btn.material : "NETHER_STAR";
        String name = btn != null && btn.name != null ? btn.name : "&e当前礼包: {gift_name}";
        List<String> lore = btn != null && btn.lore != null ? btn.lore : new java.util.ArrayList<>();

        String giftName = gift != null ? gift.name : "无";
        List<String> giftLoreList = formatGiftConfigLore(gift, player);
        String giftLore = String.join("\n", giftLoreList);

        name = placeholderResolver.applyPlaceholders(name.replace("{gift_name}", giftName), player);

        List<String> finalLore = new ArrayList<>();
        for (String loreLine : lore) {
            String processed = placeholderResolver.applyPlaceholders(loreLine, player)
                .replace("{gift_name}", giftName)
                .replace("{gift_lore}", giftLore);

            if (processed.contains("{reward_lore}")) {
                for (String rewardLine : giftLoreList) {
                    finalLore.add(processed.replace("{reward_lore}", rewardLine));
                }
            } else if (processed.contains("{reward_lore_")) {
                boolean replaced = false;
                for (int i = 0; i < giftLoreList.size(); i++) {
                    String placeholder = "{reward_lore_" + i + "}";
                    if (processed.contains(placeholder)) {
                        finalLore.add(processed.replace(placeholder, giftLoreList.get(i)));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    continue;
                }
            } else {
                finalLore.add(processed);
            }
        }
        lore = finalLore;

        Material mat;
        try {
            mat = Material.valueOf(materialStr);
        } catch (IllegalArgumentException e) {
            mat = Material.NETHER_STAR;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ConfigManager.colorize(name));
            meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
            if (btn != null && btn.customModelData > 0) {
                meta.setCustomModelData(btn.customModelData);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInviteCodeItem(Player player, MenuConfig config, String inviteCode) {
        ButtonConfig btn = config.buttons.get("C");
        String materialStr = btn != null ? btn.material : "NAME_TAG";
        String name = btn != null && btn.name != null ? btn.name : "&6&l我的邀请码";
        List<String> lore = btn != null && btn.lore != null ? btn.lore : new java.util.ArrayList<>();

        String displayCode = inviteCode != null ? inviteCode : "无";

        name = placeholderResolver.applyPlaceholders(name.replace("{invite_code}", displayCode), player);
        lore = lore.stream()
            .map(s -> placeholderResolver.applyPlaceholders(s, player))
            .map(s -> s.replace("{invite_code}", displayCode))
            .toList();

        Material mat;
        try {
            mat = Material.valueOf(materialStr);
        } catch (IllegalArgumentException e) {
            mat = Material.NAME_TAG;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ConfigManager.colorize(name));
            meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
            if (btn != null && btn.customModelData > 0) {
                meta.setCustomModelData(btn.customModelData);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> formatGiftConfigLore(GiftManager.GiftConfig gift, Player player) {
        if (gift == null) {
            return List.of("&c无");
        }
        if (gift.lore != null && !gift.lore.isEmpty()) {
            return gift.lore.stream()
                .map(line -> placeholderResolver.applyPlaceholders(line, player))
                .toList();
        }
        return formatGiftRewardsLore(gift);
    }

    private List<String> formatMilestoneRewardsLore(MilestoneManager.Milestone milestone) {
        if (milestone == null) {
            return List.of("&c无");
        }
        if (milestone.lore != null && !milestone.lore.isEmpty()) {
            return new ArrayList<>(milestone.lore);
        }
        if (milestone.rewards == null || milestone.rewards.isEmpty()) {
            return List.of("&c无奖励");
        }
        List<String> lore = new ArrayList<>();
        for (MilestoneManager.Reward reward : milestone.rewards) {
            String rewardText = switch (reward.type) {
                case "money" -> "&a" + reward.value + " 金币";
                case "points" -> "&b" + reward.value + " 点券";
                case "item" -> "&e" + reward.value;
                case "command" -> "&f" + reward.value;
                default -> "&7" + reward.type + ": " + reward.value;
            };
            lore.add(rewardText);
        }
        return lore;
    }

    private List<String> formatGiftRewardsLore(GiftManager.GiftConfig gift) {
        if (gift == null || gift.rewards == null) {
            return List.of("&c无");
        }
        List<String> lore = new ArrayList<>();
        for (MilestoneManager.Reward reward : gift.rewards) {
            String rewardText = switch (reward.type) {
                case "money" -> "&a" + reward.value + " 金币";
                case "points" -> "&b" + reward.value + " 点券";
                case "item" -> "&e" + reward.value;
                case "command" -> "&f" + reward.value;
                default -> "&7" + reward.type + ": " + reward.value;
            };
            lore.add(rewardText);
        }
        return lore;
    }

    private void createShopInventory(Player player, MenuConfig config, Inventory inventory, Map<Integer, String> slotActions) {
        // 创建新的 slotActions
        Map<Integer, String> localSlotActions = new HashMap<>(slotActions);
        
        plugin.getDatabaseManager().getPurchasedGifts(player.getUniqueId()).thenAccept(purchasedGifts -> {
            plugin.getDatabaseManager().getGiftId(player.getUniqueId()).thenAccept(currentGiftId -> {
                Map<String, GiftManager.GiftConfig> gifts = plugin.getGiftManager().getAllGifts();

                // 获取当前页码
                MenuSession session = MenuSessionManager.getInstance().getSession(player);
                int page = session != null ? session.getPage() : 0;

                // 选择使用哪个形状（根据页码）
                List<String> currentShape = page == 0 ? config.shape : (config.shape2 != null && !config.shape2.isEmpty() ? config.shape2 : config.shape);
                
                // 收集所有 G 位置的槽位
                List<Integer> giftSlots = new ArrayList<>();
                int shapeSlot = 0;
                for (String row : currentShape) {
                    for (char c : row.toCharArray()) {
                        if (c == 'G') {
                            giftSlots.add(shapeSlot);
                        }
                        shapeSlot++;
                    }
                }
                
                // 计算每页显示的礼包数量（基于布局中的 G 位置数量）
                int giftsPerPage = giftSlots.size();
                List<GiftManager.GiftConfig> giftList = new ArrayList<>(gifts.values());
                int startIndex = page * giftsPerPage;
                int endIndex = Math.min(startIndex + giftsPerPage, giftList.size());
                List<GiftManager.GiftConfig> currentPageGifts = giftList.subList(startIndex, endIndex);
                
                // 计算总页数
                int totalPages = (int) Math.ceil((double) giftList.size() / giftsPerPage);
                // 确保总页数至少为1
                totalPages = Math.max(totalPages, 1);

                // 放置礼包物品
                for (int i = 0; i < currentPageGifts.size(); i++) {
                    if (i >= giftSlots.size()) {
                        break; // 没有足够的 G 位置
                    }
                    
                    GiftManager.GiftConfig gift = currentPageGifts.get(i);
                    boolean isPurchased = purchasedGifts.contains(gift.id);
                    boolean isCurrent = currentGiftId != null && currentGiftId.equals(gift.id);
                    String state = isCurrent ? "current" : (isPurchased ? "purchased" : "available");
                    int giftSlot = giftSlots.get(i);

                    ButtonConfig btn = config.buttons.get("G");
                    ButtonConfig stateBtn = btn != null && btn.stateOverrides != null ?
                        btn.stateOverrides.get(state) : null;

                    String materialStr;
                    String name;
                    List<String> lore;
                    int customModelData;

                    if (stateBtn != null) {
                        materialStr = stateBtn.material != null && !stateBtn.material.contains("{") ?
                            stateBtn.material : gift.material.name();
                        name = stateBtn.name != null && !stateBtn.name.contains("{") ?
                            stateBtn.name : gift.name;
                        lore = stateBtn.lore != null && !stateBtn.lore.isEmpty() ?
                            stateBtn.lore : btn.lore;
                        customModelData = stateBtn.customModelData;
                    } else {
                        materialStr = gift.material.name();
                        name = gift.name;
                        lore = btn.lore;
                        customModelData = gift.customModelData;
                    }

                    name = placeholderResolver.applyPlaceholders(name.replace("{name}", gift.name), player);

                    List<String> rewardLore = formatGiftConfigLore(gift, player);
                    String durationText = gift.durationDays == 0 ? "永久" : gift.durationDays + "天";
                    List<String> finalLore = new ArrayList<>();
                    for (String line : lore) {
                        String processed = placeholderResolver.applyPlaceholders(line, player);
                        processed = processed.replace("{price_money}", String.valueOf(gift.priceMoney))
                            .replace("{price_points}", String.valueOf(gift.pricePoints))
                            .replace("{duration_days}", String.valueOf(gift.durationDays))
                            .replace("{duration_text}", durationText);

                        if (processed.contains("{reward_lore}")) {
                            for (String rewardLine : rewardLore) {
                                finalLore.add(processed.replace("{reward_lore}", rewardLine));
                            }
                        } else if (processed.contains("{reward_lore_")) {
                            boolean replaced = false;
                            for (int j = 0; j < rewardLore.size(); j++) {
                                String placeholder = "{reward_lore_" + j + "}";
                                if (processed.contains(placeholder)) {
                                    finalLore.add(processed.replace(placeholder, rewardLore.get(j)));
                                    replaced = true;
                                    break;
                                }
                            }
                            if (!replaced) {
                                continue;
                            }
                        } else {
                            finalLore.add(processed);
                        }
                    }
                    lore = finalLore;

                    Material mat;
                    try {
                        mat = Material.valueOf(materialStr);
                    } catch (IllegalArgumentException e) {
                        mat = Material.DIAMOND;
                    }

                    ItemStack item = new ItemStack(mat);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ConfigManager.colorize(name));
                        meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
                        if (customModelData > 0) {
                            meta.setCustomModelData(customModelData);
                        }
                        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                        item.setItemMeta(meta);
                    }

                    if (giftSlot < inventory.getSize()) {
                        inventory.setItem(giftSlot, item);
                    }
                    localSlotActions.put(giftSlot, "BUY_GIFT");
                }

                int layoutSlot = 0;
                for (String row : currentShape) {
                    for (char c : row.toCharArray()) {
                        if (c == '#') {
                            if (inventory.getItem(layoutSlot) == null) {
                                inventory.setItem(layoutSlot, createBackgroundItem(config));
                            }
                        } else if (c == 'C') {
                            ButtonConfig closeBtn = config.buttons.get("C");
                            if (closeBtn != null) {
                                inventory.setItem(layoutSlot, createButtonItem(player, closeBtn));
                                localSlotActions.put(layoutSlot, "CLOSE");
                            }
                        } else if (c == 'A') {
                            ButtonConfig mainBtn = config.buttons.get("A");
                            if (mainBtn != null) {
                                inventory.setItem(layoutSlot, createButtonItem(player, mainBtn));
                                localSlotActions.put(layoutSlot, "OPEN_MAIN");
                            }
                        } else if (c == 'R') {
                            ButtonConfig backBtn = config.buttons.get("R");
                            if (backBtn != null) {
                                inventory.setItem(layoutSlot, createButtonItem(player, backBtn));
                                localSlotActions.put(layoutSlot, "BACK_TO_VETERAN");
                            }
                        } else if (c == 'P') { // 上一页按钮
                            ButtonConfig prevBtn = config.buttons.get("P");
                            if (prevBtn != null) {
                                ItemStack item = createButtonItem(player, prevBtn);
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    // 设置页码信息
                                    List<String> lore = new ArrayList<>();
                                    lore.add(placeholderResolver.applyPlaceholders("&7当前页: " + (page + 1) + "/" + totalPages, player));
                                    lore.add(placeholderResolver.applyPlaceholders("&7查看上一页礼包", player));

                                    // 如果是第一页，禁用上一页按钮
                                    if (page == 0) {
                                        meta.setDisplayName(ConfigManager.colorize(placeholderResolver.applyPlaceholders(prevBtn.name, player)));
                                        lore.clear();
                                        lore.add(placeholderResolver.applyPlaceholders("&7当前页: 1/" + totalPages, player));
                                        lore.add(placeholderResolver.applyPlaceholders("&7已经是第一页", player));
                                    }
                                    meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
                                    item.setItemMeta(meta);
                                }
                                inventory.setItem(layoutSlot, item);
                                localSlotActions.put(layoutSlot, "PREV_PAGE");
                            }
                        } else if (c == 'N') { // 下一页按钮
                            ButtonConfig nextBtn = config.buttons.get("N");
                            if (nextBtn != null) {
                                ItemStack item = createButtonItem(player, nextBtn);
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    // 设置页码信息
                                    List<String> lore = new ArrayList<>();
                                    lore.add(placeholderResolver.applyPlaceholders("&7当前页: " + (page + 1) + "/" + totalPages, player));
                                    lore.add(placeholderResolver.applyPlaceholders("&7查看下一页礼包", player));

                                    // 如果是最后一页，禁用下一页按钮
                                    if (endIndex >= giftList.size()) {
                                        meta.setDisplayName(ConfigManager.colorize(placeholderResolver.applyPlaceholders(nextBtn.name, player)));
                                        lore.clear();
                                        lore.add(placeholderResolver.applyPlaceholders("&7当前页: " + totalPages + "/" + totalPages, player));
                                        lore.add(placeholderResolver.applyPlaceholders("&7已经是最后一页", player));
                                    }
                                    meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
                                    item.setItemMeta(meta);
                                }
                                inventory.setItem(layoutSlot, item);
                                localSlotActions.put(layoutSlot, "NEXT_PAGE");
                            }
                        } else if (c != 'G') {
                            ButtonConfig btn = config.buttons.get(String.valueOf(c));
                            if (btn != null) {
                                inventory.setItem(layoutSlot, createButtonItem(player, btn));
                            }
                        }
                        layoutSlot++;
                    }
                }

                final Inventory inv = inventory;
                final Map<Integer, String> finalSlotActions = localSlotActions;
                final int finalPage = page;
                
                // 更新现有的 session
                MenuSession existingSession = MenuSessionManager.getInstance().getSession(player);
                if (existingSession != null) {
                    existingSession.updateSlotActions(finalSlotActions);
                    existingSession.setPage(finalPage);
                }
                
                SchedulerUtils.runTask(plugin, () -> {
                    player.openInventory(inv);
                });
            });
        });
    }

    private ItemStack createCurrentGiftPreviewSync(Player player) {
        String giftId = plugin.getDatabaseManager().getGiftId(player.getUniqueId()).join();
        String defaultGiftId = plugin.getConfigManager().getConfig()
            .getString("new_player_reward.default_gift_id", "default");
        boolean requireGift = plugin.getConfigManager().getConfig()
            .getBoolean("new_player_reward.require_gift", false);

        String finalGiftId = giftId;
        if (finalGiftId == null) {
            if (requireGift) {
                finalGiftId = "none";
            } else {
                finalGiftId = defaultGiftId;
            }
        }

        GiftManager.GiftConfig gift = plugin.getGiftManager().getGift(finalGiftId);

        ItemStack item;
        if (gift != null) {
            item = new ItemStack(gift.material);
            ItemMeta meta = item.getItemMeta();
            String name = plugin.getConfigManager().getMenusConfig().getString("shop_menu.buttons.W.name", "&e当前生效礼包: {name}");
            name = name.replace("{name}", gift.name);
            // 先解析 PAPI 占位符，再颜色化
            name = placeholderResolver.applyPlaceholders(name, player);
            meta.setDisplayName(ConfigManager.colorize(name));

            List<String> lore = plugin.getConfigManager().getMenusConfig().getStringList("shop_menu.buttons.W.lore");
            List<String> loreComponents = new ArrayList<>();
            for (String line : lore) {
                if (line.contains("{lore_lines}")) {
                    for (String giftLore : gift.lore) {
                        String resolved = placeholderResolver.applyPlaceholders("&7" + giftLore, player);
                        loreComponents.add(ConfigManager.colorize(resolved));
                    }
                } else {
                    String resolved = placeholderResolver.applyPlaceholders(line, player);
                    loreComponents.add(ConfigManager.colorize(resolved));
                }
            }
            meta.setLore(loreComponents);
            if (gift.customModelData > 0) {
                meta.setCustomModelData(gift.customModelData);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        } else {
            item = new ItemStack(Material.valueOf(
                plugin.getConfigManager().getMenusConfig().getString("gift_shop.default_gift_preview.material", "BARRIER")));
            ItemMeta meta = item.getItemMeta();
            String name = plugin.getConfigManager().getMenusConfig().getString("gift_shop.default_gift_preview.name", "&c未购买礼包");
            name = placeholderResolver.applyPlaceholders(name, player);
            meta.setDisplayName(ConfigManager.colorize(name));
            List<String> lore = plugin.getConfigManager().getMenusConfig().getStringList("gift_shop.default_gift_preview.lore");
            meta.setLore(lore.stream()
                .map(line -> placeholderResolver.applyPlaceholders(line, player))
                .map(ConfigManager::colorize)
                .toList());
            int cmd = plugin.getConfigManager().getMenusConfig().getInt("gift_shop.default_gift_preview.custom-model-data", 0);
            if (cmd > 0) {
                meta.setCustomModelData(cmd);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }

        return item;
    }

    private ItemStack createBackgroundItem(MenuConfig config) {
        ItemStack item = new ItemStack(Material.valueOf(config.backgroundMaterial));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ConfigManager.colorize(config.backgroundName));
        meta.setLore(config.backgroundLore.stream().map(ConfigManager::colorize).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createButtonItem(Player player, ButtonConfig btn) {
        ItemStack item = new ItemStack(Material.valueOf(btn.material));
        ItemMeta meta = item.getItemMeta();
        String name = placeholderResolver.applyPlaceholders(btn.name, player);
        List<String> lore = btn.lore.stream()
            .map(s -> placeholderResolver.applyPlaceholders(s, player))
            .toList();
        meta.setDisplayName(ConfigManager.colorize(name));
        meta.setLore(lore.stream().map(ConfigManager::colorize).toList());
        if (btn.customModelData > 0) {
            meta.setCustomModelData(btn.customModelData);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String getStatusText(String state) {
        return switch (state) {
            case "locked" -> "未解锁";
            case "available" -> "可领取";
            case "claimed" -> "已领取";
            default -> state;
        };
    }

    private Set<String> parseJsonArray(String json) {
        if (json == null || json.equals("[]")) {
            return new HashSet<>();
        }
        String trimmed = json.substring(1, json.length() - 1);
        if (trimmed.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> result = new HashSet<>();
        for (String s : trimmed.split(",")) {
            result.add(s.replace("\"", "").trim());
        }
        return result;
    }

    public String getButtonAction(String menuName, String buttonKey) {
        MenuConfig config = menus.get(menuName);
        if (config == null) return "NONE";
        ButtonConfig btn = config.buttons.get(buttonKey);
        return btn != null ? btn.action : "NONE";
    }

    public static class MenuConfig {
        public String title;
        public int pages; // 总页数
        public List<String> shape;
        public List<String> shape2; // 第二页布局
        public Map<String, ButtonConfig> buttons;
        public String backgroundMaterial;
        public String backgroundName;
        public List<String> backgroundLore;
    }

    public static class ButtonConfig {
        public String material;
        public String name;
        public List<String> lore;
        public String action;
        public boolean isDynamic;
        public Map<String, ButtonConfig> stateOverrides;
        public int customModelData;
    }
}
