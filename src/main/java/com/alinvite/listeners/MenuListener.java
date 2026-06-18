package com.alinvite.listeners;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.gui.MenuHolder;
import com.alinvite.gui.MenuSession;
import com.alinvite.gui.MenuSessionManager;
import com.alinvite.manager.GiftManager;
import com.alinvite.manager.MilestoneManager;
import com.alinvite.utils.SchedulerUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class MenuListener implements Listener {

    private final ALInvite plugin;
    private final ConcurrentHashMap<UUID, InputState> inputStates = new ConcurrentHashMap<>();

    public MenuListener(ALInvite plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        MenuSession session = MenuSessionManager.getInstance().getSession(player);
        
        Inventory clickedInventory = event.getInventory();
        boolean hasValidHolder = clickedInventory.getHolder() instanceof MenuHolder;
        
        if (isDebug()) {
            plugin.getLogger().info("[DEBUG] onInventoryClick - player: " + player.getName());
            plugin.getLogger().info("[DEBUG] hasValidHolder: " + hasValidHolder);
            plugin.getLogger().info("[DEBUG] session != null: " + (session != null));
            if (session != null) {
                plugin.getLogger().info("[DEBUG] session menuType: " + session.getMenuType());
                plugin.getLogger().info("[DEBUG] session.isValid(): " + session.isValid());
            }
            plugin.getLogger().info("[DEBUG] clickedInventory: " + clickedInventory);
            plugin.getLogger().info("[DEBUG] event.getRawSlot(): " + event.getRawSlot());
            plugin.getLogger().info("[DEBUG] event.getSlot(): " + event.getSlot());
        }
        
        // 如果玩家打开了我们的菜单，取消所有点击事件（防止操作玩家背包）
        if (hasValidHolder) {
            event.setCancelled(true);
            
            if (session == null || !session.isValid()) {
                if (isDebug()) plugin.getLogger().info("[DEBUG] session is null or invalid");
                MenuSessionManager.getInstance().removeSession(player);
                return;
            }
            
            if (session.getInventory() != clickedInventory) {
                if (isDebug()) plugin.getLogger().info("[DEBUG] session inventory mismatch");
                return;
            }
            
            int rawSlot = event.getRawSlot();
            if (rawSlot < 0 || rawSlot >= clickedInventory.getSize()) {
                if (isDebug()) plugin.getLogger().info("[DEBUG] slot out of bounds: " + rawSlot + ", menu size: " + clickedInventory.getSize());
                return;
            }

            ItemStack item = clickedInventory.getItem(rawSlot);
            if (item == null || item.getType() == Material.AIR) {
                if (isDebug()) plugin.getLogger().info("[DEBUG] item is null or air at slot " + rawSlot);
                return;
            }

            String menuType = session.getMenuType();
            if (isDebug()) plugin.getLogger().info("[DEBUG] Clicked - menuType: " + menuType + ", slot: " + rawSlot);

            switch (menuType) {
                case "main" -> handleMainMenuClick(player, rawSlot, session);
                case "veteran" -> handleVeteranMenuClick(player, rawSlot, session);
                case "shop" -> handleShopMenuClick(player, rawSlot, session);
            }
            return;
        }
        
        // 如果不是我们的菜单，不取消点击，让玩家可以正常使用背包
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory draggedInventory = event.getInventory();
        boolean hasValidHolder = draggedInventory.getHolder() instanceof MenuHolder;

        if (hasValidHolder) {
            event.setCancelled(true);
            
            MenuSession session = MenuSessionManager.getInstance().getSession(player);
            if (session == null || !session.isValid()) {
                MenuSessionManager.getInstance().removeSession(player);
                return;
            }

            if (session.getInventory() != draggedInventory) {
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory closedInventory = event.getInventory();
        boolean isOurMenu = closedInventory.getHolder() instanceof MenuHolder;

        if (isOurMenu) {
            // 延迟移除 session，给子菜单打开留出时间
            SchedulerUtils.runTaskLater(plugin, () -> {
                MenuSession session = MenuSessionManager.getInstance().getSession(player);
                if (session != null) {
                    // 检查是否已经打开了新菜单
                    Inventory openInventory = player.getOpenInventory().getTopInventory();
                    if (!(openInventory.getHolder() instanceof MenuHolder)) {
                        // 如果没有打开新菜单，才移除 session
                        MenuSessionManager.getInstance().removeSession(player);
                        if (isDebug()) plugin.getLogger().info("[DEBUG] Session removed after delay");
                    }
                }
            }, 1); // 延迟 1 tick
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        InputState state = inputStates.get(player.getUniqueId());

        if (state == null) {
            return;
        }

        event.setCancelled(true);

        if ("CODE".equals(state.type)) {
            if (state.timestamp > 0 && System.currentTimeMillis() > state.timestamp) {
                if (state.timeoutCallback != null) {
                    state.timeoutCallback.accept(player);
                } else {
                    inputStates.remove(player.getUniqueId());
                    player.sendMessage(plugin.getConfigManager().getMessage("dialog.timeout", player));
                }
                return;
            }

            String cancelKey = plugin.getConfigManager().getConfig().getString("input_dialog.cancel_key", "Q");
            String code = event.getMessage().trim();

            if (code.equalsIgnoreCase(cancelKey)) {
                player.sendMessage(plugin.getConfigManager().getMessage("dialog.cancel", player));
                inputStates.remove(player.getUniqueId());
                return;
            }

            if (code.isEmpty()) {
                player.sendMessage(ConfigManager.colorize("&c邀请码不能为空！"));
                return;
            }

            plugin.getInviteManager().bindInviteCode(player, code).thenAccept(result -> {
                if (result.success) {
                    player.sendMessage(plugin.getConfigManager().getMessage("dialog.success", player));
                } else {
                    String reason = switch (result.type) {
                        case NO_PERMISSION -> plugin.getConfigManager().getMessage("errors.no_permission", player);
                        case CODE_NOT_FOUND -> plugin.getConfigManager().getMessage("dialog.fail", player);
                        case ALREADY_USED -> plugin.getConfigManager().getMessage("errors.already_used", player);
                        case IP_LIMIT -> plugin.getConfigManager().getMessage("dialog.ip_limit", player);
                        case SELF_INVITE -> plugin.getConfigManager().getMessage("dialog.self_invite", player);
                        case VETERAN_CANNOT_BIND -> plugin.getConfigManager().getMessage("errors.veteran_cannot_bind", player);
                        default -> plugin.getConfigManager().getMessage("dialog.fail", player);
                    };
                    player.sendMessage(reason);
                }
            });
            inputStates.remove(player.getUniqueId());
        }
    }

    private void handleMainMenuClick(Player player, int slot, MenuSession session) {
        String action = session.getAction(slot);
        if (action == null) {
            if (isDebug()) plugin.getLogger().info("[DEBUG] No action for slot " + slot + " in main menu");
            return;
        }

        if (isDebug()) plugin.getLogger().info("[DEBUG] Main menu action: " + action);

        switch (action) {
            case "INPUT_CODE" -> startCodeInput(player);
            case "OPEN_VETERAN" -> plugin.getMenuManager().openVeteranMenu(player);
            case "OPEN_SHOP" -> plugin.getMenuManager().openShopMenu(player);
        }
    }

    private void handleVeteranMenuClick(Player player, int slot, MenuSession session) {
        String action = session.getAction(slot);
        if (action == null) {
            if (isDebug()) plugin.getLogger().info("[DEBUG] No action for slot " + slot + " in veteran menu");
            return;
        }

        if (isDebug()) plugin.getLogger().info("[DEBUG] Veteran menu action: " + action);

        switch (action) {
            case "OPEN_SHOP" -> plugin.getMenuManager().openShopMenu(player);
            case "CLOSE" -> player.closeInventory();
            case "CLAIM_MILESTONE" -> handleClaimMilestone(player, slot);
            case "PREV_PAGE" -> handlePrevPage(player, session, "veteran");
            case "NEXT_PAGE" -> handleNextPage(player, session, "veteran");
            case "OPEN_MAIN" -> plugin.getMenuManager().openMainMenu(player);
        }
    }

    private void handleClaimMilestone(Player player, int slot) {
        int milestoneIndex = slot - 10;
        var milestones = plugin.getMilestoneManager().getMilestones();
        if (milestoneIndex < 0 || milestoneIndex >= milestones.size()) {
            return;
        }

        var entry = milestones.entrySet().stream().skip(milestoneIndex).findFirst().orElse(null);
        if (entry == null) return;

        int required = entry.getKey();
        MilestoneManager.Milestone milestone = entry.getValue();

        plugin.getDatabaseManager().getClaimedMilestones(player.getUniqueId()).thenAccept(claimedJson -> {
            plugin.getDatabaseManager().getPlayerData(player.getUniqueId()).thenAccept(data -> {
                int total = data != null ? data.totalInvites : 0;

                Set<String> claimedSet = new HashSet<>();
                if (claimedJson != null && !claimedJson.trim().isEmpty() && !claimedJson.equals("[]")) {
                    try {
                        String[] parts = claimedJson.replace("[", "").replace("]", "").replace("\"", "").split(",");
                        for (String part : parts) {
                            part = part.trim();
                            if (!part.isEmpty()) {
                                claimedSet.add(part);
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("解析已领取里程碑失败: " + claimedJson);
                    }
                }

                if (total >= required) {
                    if (claimedSet.contains(String.valueOf(required))) {
                        SchedulerUtils.runTask(plugin, () ->
                            player.sendMessage(plugin.getConfigManager().getMessage("milestone.already_claimed", player)));
                    } else {
                        SchedulerUtils.runTask(plugin, () -> {
                            plugin.getMilestoneManager().giveRewards(player, milestone);
                            plugin.getDatabaseManager().claimMilestone(player.getUniqueId(), String.valueOf(required)).thenAccept(v -> {
                                SchedulerUtils.runTask(plugin, () -> {
                                    player.sendMessage(plugin.getConfigManager().getMessage("milestone.claim_success", player)
                                        .replace("{name}", milestone.name));

                                    if (plugin.getConfigManager().getConfig().getBoolean("announcements.enabled", true)) {
                                        boolean sendOnlyOnce = plugin.getConfigManager().getConfig().getBoolean("announcements.send_only_once", true);
                                        if (sendOnlyOnce) {
                                            plugin.getDatabaseManager().getAnnouncedMilestones(player.getUniqueId()).thenAccept(announced -> {
                                                if (!announced.contains(String.valueOf(required))) {
                                                    plugin.getMilestoneManager().sendAnnouncement(player, milestone, total);
                                                    plugin.getDatabaseManager().addAnnouncedMilestone(player.getUniqueId(), String.valueOf(required));
                                                }
                                            });
                                        } else {
                                            plugin.getMilestoneManager().sendAnnouncement(player, milestone, total);
                                        }
                                    }

                                    player.closeInventory();
                                    SchedulerUtils.runTaskLater(plugin, () -> {
                                        plugin.getMenuManager().openVeteranMenu(player);
                                    }, 1L);
                                });
                            });
                        });
                    }
                } else {
                    SchedulerUtils.runTask(plugin, () ->
                        player.sendMessage(plugin.getConfigManager().getMessage("milestone.not_unlocked", player)
                            .replace("{name}", milestone.name)
                            .replace("{remaining}", String.valueOf(required - total))));
                }
            });
        });
    }

    private void handleShopMenuClick(Player player, int slot, MenuSession session) {
        String action = session.getAction(slot);
        if (action == null) {
            if (isDebug()) plugin.getLogger().info("[DEBUG] No action for slot " + slot + " in shop menu");
            return;
        }

        if (isDebug()) plugin.getLogger().info("[DEBUG] Shop menu action: " + action);

        switch (action) {
            case "BACK_TO_VETERAN" -> plugin.getMenuManager().openVeteranMenu(player);
            case "CLOSE" -> player.closeInventory();
            case "BUY_GIFT" -> handleBuyGift(player, slot);
            case "PREV_PAGE" -> handlePrevPage(player, session, "shop");
            case "NEXT_PAGE" -> handleNextPage(player, session, "shop");
            case "OPEN_MAIN" -> plugin.getMenuManager().openMainMenu(player);
        }
    }

    private void handleBuyGift(Player player, int slot) {
        var giftList = plugin.getGiftManager().getAllGifts();
        int giftsPerPage = 7;
        int page = 0;
        
        MenuSession session = MenuSessionManager.getInstance().getSession(player);
        if (session != null) {
            page = session.getPage();
        }
        
        int giftIndex = slot - 10;
        if (giftIndex < 0) {
            return;
        }
        
        int absoluteIndex = page * giftsPerPage + giftIndex;
        var giftListValues = new java.util.ArrayList<>(giftList.values());
        
        if (absoluteIndex >= giftListValues.size()) {
            return;
        }

        GiftManager.GiftConfig gift = giftListValues.get(absoluteIndex);
        plugin.getDatabaseManager().getGiftId(player.getUniqueId()).thenAccept(currentGiftId -> {
            if (gift.id.equals(currentGiftId)) {
                player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.already_active", player)
                    .replace("{gift_name}", ConfigManager.colorize(gift.name)));
                return;
            }

            plugin.getDatabaseManager().getPurchasedGifts(player.getUniqueId()).thenAccept(purchasedGifts -> {
                boolean isPurchased = purchasedGifts.contains(gift.id);
                if (isPurchased) {
                    plugin.getGiftManager().switchGift(player, gift.id).thenAccept(v -> {
                        player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.switch_success", player)
                            .replace("{gift_name}", ConfigManager.colorize(gift.name)));
                        plugin.getMenuManager().openVeteranMenu(player);
                    });
                } else {
                    plugin.getGiftManager().buyGift(player, gift.id).thenAccept(result -> {
                        if (result.success) {
                            player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.success", player)
                                .replace("{gift_name}", ConfigManager.colorize(gift.name)));
                            plugin.getMenuManager().openShopMenu(player);
                        } else {
                            String reason = switch (result.type) {
                                case INSUFFICIENT_MONEY -> plugin.getConfigManager()
                                    .getMessage("commands.buygift.fail_money", player).replace("{price}", String.valueOf(gift.priceMoney));
                                case INSUFFICIENT_POINTS -> plugin.getConfigManager()
                                    .getMessage("commands.buygift.fail_points", player).replace("{price}", String.valueOf(gift.pricePoints));
                                case NOT_FOUND -> "&c礼包不存在！";
                                default -> plugin.getConfigManager().getMessage("errors.unknown", player);
                            };
                            player.sendMessage(reason);
                        }
                    });
                }
            });
        });
    }

    private void handlePrevPage(Player player, MenuSession session, String menuType) {
        if (session.getPage() > 0) {
            session.setPage(session.getPage() - 1);
            if ("veteran".equals(menuType)) {
                plugin.getMenuManager().openVeteranMenu(player);
            } else if ("shop".equals(menuType)) {
                plugin.getMenuManager().openShopMenu(player);
            }
        }
    }

    private void handleNextPage(Player player, MenuSession session, String menuType) {
        int maxPage = getMaxPage(menuType);
        if (session.getPage() < maxPage) {
            session.setPage(session.getPage() + 1);
            if ("veteran".equals(menuType)) {
                plugin.getMenuManager().openVeteranMenu(player);
            } else if ("shop".equals(menuType)) {
                plugin.getMenuManager().openShopMenu(player);
            }
        }
    }

    private int getMaxPage(String menuType) {
        if ("veteran".equals(menuType)) {
            int milestonesPerPage = 14;
            int totalMilestones = plugin.getMilestoneManager().getMilestones().size();
            return Math.max(0, (int) Math.ceil((double) totalMilestones / milestonesPerPage) - 1);
        } else if ("shop".equals(menuType)) {
            int giftsPerPage = 7;
            int totalGifts = plugin.getGiftManager().getAllGifts().size();
            return Math.max(0, (int) Math.ceil((double) totalGifts / giftsPerPage) - 1);
        }
        return 0;
    }

    public void startCodeInput(Player player) {
        String usePerm = plugin.getConfigManager().getConfig()
            .getString("invite_code.use_permission", "alinvite.use");
        if (!player.hasPermission(usePerm)) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission", player));
            return;
        }

        int timeoutSeconds = plugin.getConfigManager().getConfig().getInt("input_dialog.timeout", 30);
        long timeoutTimestamp = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        Consumer<Player> timeoutCallback = p -> {
            inputStates.remove(p.getUniqueId());
            p.sendMessage(plugin.getConfigManager().getMessage("dialog.timeout", p));
        };

        inputStates.put(player.getUniqueId(), new InputState("CODE", timeoutTimestamp, timeoutCallback));
        player.closeInventory();
        String cancelKey = plugin.getConfigManager().getConfig().getString("input_dialog.cancel_key", "Q");
        String prompt = plugin.getConfigManager().getMessage("dialog.prompt", player).replace("{cancel_key}", cancelKey);
        player.sendMessage(prompt);
    }

    private boolean isDebug() {
        return plugin.getConfigManager().getConfig().getBoolean("debug", false);
    }

    private static class InputState {
        final String type;
        final long timestamp;
        final Consumer<Player> timeoutCallback;

        InputState(String type) {
            this(type, -1, null);
        }

        InputState(String type, long timestamp, Consumer<Player> timeoutCallback) {
            this.type = type;
            this.timestamp = timestamp;
            this.timeoutCallback = timeoutCallback;
        }
    }
}