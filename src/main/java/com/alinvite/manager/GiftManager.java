package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.utils.SchedulerUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.Material;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class GiftManager {
    private final ALInvite plugin;
    private final Map<String, GiftConfig> gifts;
    private final List<String> giftOrder;

    public GiftManager(ALInvite plugin) {
        this.plugin = plugin;
        this.gifts = new LinkedHashMap<>();
        this.giftOrder = new ArrayList<>();
        loadGifts();
    }

    public static class GiftConfig {
        public final String id;
        public final String name;
        public final List<MilestoneManager.Reward> rewards;
        public final Material material;
        public final int customModelData;
        public final List<String> lore;
        public final double priceMoney;
        public final int pricePoints;
        public final int durationDays;

        public GiftConfig(String id, String name, List<MilestoneManager.Reward> rewards, Material material, int customModelData, List<String> lore, double priceMoney, int pricePoints, int durationDays) {
            this.id = id;
            this.name = name;
            this.rewards = rewards;
            this.material = material;
            this.customModelData = customModelData;
            this.lore = lore;
            this.priceMoney = priceMoney;
            this.pricePoints = pricePoints;
            this.durationDays = durationDays;
        }
    }

    private void loadGifts() {
        ConfigurationSection giftsConfig = plugin.getConfigManager().getConfig().getConfigurationSection("gift_shop.gifts");
        if (giftsConfig == null) return;

        gifts.clear();
        giftOrder.clear();

        for (String giftId : giftsConfig.getKeys(false)) {
            ConfigurationSection giftConfig = giftsConfig.getConfigurationSection(giftId);
            if (giftConfig != null) {
                String name = giftConfig.getString("name", "礼包");
                List<MilestoneManager.Reward> rewards = loadRewards("gift_shop.gifts." + giftId + ".rewards");
                Material material = Material.valueOf(giftConfig.getString("material", "CHEST"));
                int customModelData = giftConfig.getInt("custom_model_data", 0);
                List<String> lore = giftConfig.getStringList("lore");
                double priceMoney = giftConfig.getDouble("price_money", 0.0);
                int pricePoints = giftConfig.getInt("price_points", 0);
                int durationDays = giftConfig.getInt("duration_days", 0);
                gifts.put(giftId, new GiftConfig(giftId, name, rewards, material, customModelData, lore, priceMoney, pricePoints, durationDays));
                giftOrder.add(giftId);
            }
        }
    }

    private List<MilestoneManager.Reward> loadRewards(String rewardsPath) {
        List<MilestoneManager.Reward> rewards = new ArrayList<>();
        List<Map<?, ?>> rewardList = plugin.getConfigManager().getConfig().getMapList(rewardsPath);

        for (Map<?, ?> rewardMap : rewardList) {
            String type = (String) rewardMap.get("type");
            Object value = rewardMap.get("value");
            if (type != null && value != null) {
                rewards.add(new MilestoneManager.Reward(type, value));
            }
        }
        return rewards;
    }

    public void giveGiftRewards(Player player, UUID inviterUuid) {
        // 检查玩家是否启用了礼包功能
        plugin.getDatabaseManager().getPlayerData(player.getUniqueId()).thenAccept(playerData -> {
            if (playerData != null && !playerData.giftEnabled) {
                return;
            }

            plugin.getDatabaseManager().getGiftId(inviterUuid).thenAccept(giftId -> {
                String defaultGiftId = plugin.getConfigManager().getConfig()
                    .getString("new_player_reward.default_gift_id", "default");
                boolean requireGift = plugin.getConfigManager().getConfig()
                    .getBoolean("new_player_reward.require_gift", false);

                String finalGiftId = giftId;
                if (finalGiftId == null) {
                    if (requireGift) {
                        return;
                    }
                    finalGiftId = defaultGiftId;
                }

                GiftConfig gift = gifts.get(finalGiftId);
                if (gift == null) {
                    gift = gifts.get(defaultGiftId);
                }

                if (gift == null) {
                    return;
                }

                for (MilestoneManager.Reward reward : gift.rewards) {
                    switch (reward.type) {
                        case "command" -> {
                            String command = reward.value.toString()
                                .replace("%player%", player.getName());
                            SchedulerUtils.runTask(plugin, () ->
                                Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command));
                        }
                        case "money" -> {
                            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = plugin.getServer().getServicesManager()
                                .getRegistration(net.milkbowl.vault.economy.Economy.class);
                            if (rsp != null) {
                                net.milkbowl.vault.economy.Economy economy = rsp.getProvider();
                                if (economy != null) {
                                    economy.depositPlayer(player, Double.parseDouble(reward.value.toString()));
                                }
                            }
                        }
                        case "points" -> {
                            givePoints(player, reward.value);
                        }
                        case "item" -> {
                            giveItem(player, reward.value);
                        }
                    }
                }
            });
        });
    }

    private void givePoints(Player player, Object value) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "NONE");
        if ("NONE".equals(pointsType)) return;

        int amount = Integer.parseInt(value.toString());

        switch (pointsType) {
            case "PLAYERPOINTS" -> {
                try {
                    Object api = getPlayerPointsAPI();
                    if (api == null) return;
                    Method giveMethod = api.getClass().getMethod("give", UUID.class, int.class);
                    giveMethod.invoke(api, player.getUniqueId(), amount);
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints 发放失败: " + e.getMessage());
                }
            }
            case "CUSTOM" -> {
                String giveCmd = plugin.getConfigManager().getConfig()
                    .getString("economy.points_command.give", "")
                    .replace("%player%", player.getName())
                    .replace("%amount%", String.valueOf(amount));
                SchedulerUtils.runTask(plugin, () ->
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), giveCmd));
            }
        }
    }

    private void giveItem(Player player, Object value) {
        String[] parts = value.toString().split(" ");
        if (parts.length >= 2) {
            try {
                Material mat = Material.valueOf(parts[0]);
                int amount = Integer.parseInt(parts[1]);
                ItemStack item = new ItemStack(mat, amount);

                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    for (ItemStack leftItem : leftover.values()) {
                        player.getWorld().dropItem(player.getLocation(), leftItem);
                    }
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("无效的物品格式: " + value);
            }
        }
    }

    public Map<String, GiftConfig> getGifts() {
        return gifts;
    }

    public GiftConfig getGift(String giftId) {
        return gifts.get(giftId);
    }

    public Map<String, GiftConfig> getAllGifts() {
        return gifts;
    }

    public void handleSlotPurchase(Player player, int slot) {
        com.alinvite.gui.MenuSession session = com.alinvite.gui.MenuSessionManager.getInstance().getSession(player);
        if (session == null) {
            return;
        }

        List<String> shape = plugin.getConfigManager().getMenusConfig().getStringList("shop_menu.shape");
        List<String> shape2 = plugin.getConfigManager().getMenusConfig().getStringList("shop_menu.shape2");
        
        int page = session.getPage();
        List<String> currentShape = page == 0 ? shape : (shape2 != null && !shape2.isEmpty() ? shape2 : shape);

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

        int giftIndex = giftSlots.indexOf(slot);
        if (giftIndex == -1) {
            return;
        }

        int giftsPerPage = giftSlots.size();
        List<GiftConfig> giftList = new ArrayList<>(gifts.values());
        int absoluteIndex = page * giftsPerPage + giftIndex;

        if (absoluteIndex >= giftList.size()) {
            return;
        }

        GiftConfig gift = giftList.get(absoluteIndex);
        
        plugin.getDatabaseManager().getGiftId(player.getUniqueId()).thenAccept(currentGiftId -> {
            if (gift.id.equals(currentGiftId)) {
                player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.already_active", player)
                    .replace("{gift_name}", com.alinvite.config.ConfigManager.colorize(gift.name)));
                return;
            }

            plugin.getDatabaseManager().getPurchasedGifts(player.getUniqueId()).thenAccept(purchasedGifts -> {
                boolean isPurchased = purchasedGifts.contains(gift.id);
                if (isPurchased) {
                    plugin.getGiftManager().switchGift(player, gift.id).thenAccept(v -> {
                        player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.switch_success", player)
                            .replace("{gift_name}", com.alinvite.config.ConfigManager.colorize(gift.name)));
                        plugin.getMenuManager().openShopMenu(player);
                    });
                } else {
                    buyGift(player, gift.id).thenAccept(result -> {
                        if (result.success) {
                            player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.success", player)
                                .replace("{gift_name}", com.alinvite.config.ConfigManager.colorize(gift.name)));
                            plugin.getMenuManager().openShopMenu(player);
                        } else {
                            String message = switch (result.type) {
                                case INSUFFICIENT_MONEY -> plugin.getConfigManager()
                                    .getMessage("commands.buygift.fail_money", player).replace("{price}", String.valueOf(gift.priceMoney));
                                case INSUFFICIENT_POINTS -> plugin.getConfigManager()
                                    .getMessage("commands.buygift.fail_points", player).replace("{price}", String.valueOf(gift.pricePoints));
                                case NOT_FOUND -> "&c礼包不存在！";
                                case NO_PERMISSION -> plugin.getConfigManager().getMessage("commands.buygift.no_permission", player);
                                default -> "&c购买失败";
                            };
                            player.sendMessage(com.alinvite.config.ConfigManager.colorize(message));
                        }
                    });
                }
            });
        });
    }

    public CompletableFuture<Void> switchGift(Player player, String giftId) {
        return plugin.getDatabaseManager().setGiftId(player.getUniqueId(), giftId)
            .thenAccept(v -> {
                plugin.getCacheManager().setGiftId(player.getUniqueId(), giftId);
            });
    }

    public enum BuyGiftResultType {
        INSUFFICIENT_MONEY,
        INSUFFICIENT_POINTS,
        NOT_FOUND,
        NO_PERMISSION,
        SUCCESS
    }

    public static class BuyGiftResult {
        public final boolean success;
        public final BuyGiftResultType type;

        public BuyGiftResult(boolean success, BuyGiftResultType type) {
            this.success = success;
            this.type = type;
        }
    }

    public CompletableFuture<BuyGiftResult> buyGift(Player player, String giftId) {
        return CompletableFuture.supplyAsync(() -> {
            GiftConfig gift = gifts.get(giftId);
            if (gift == null) {
                return new BuyGiftResult(false, BuyGiftResultType.NOT_FOUND);
            }

            String veteranPermission = plugin.getConfigManager().getConfig()
                .getString("invite_code.veteran_permission", "alinvite.veteran");
            if (!player.hasPermission(veteranPermission)) {
                return new BuyGiftResult(false, BuyGiftResultType.NO_PERMISSION);
            }

            return SchedulerUtils.runTaskSupplied(plugin, () -> {
                RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = plugin.getServer().getServicesManager()
                    .getRegistration(net.milkbowl.vault.economy.Economy.class);
                net.milkbowl.vault.economy.Economy economy = rsp != null ? rsp.getProvider() : null;

                if (economy != null && gift.priceMoney > 0) {
                    if (!economy.has(player, gift.priceMoney)) {
                        return new BuyGiftResult(false, BuyGiftResultType.INSUFFICIENT_MONEY);
                    }
                    economy.withdrawPlayer(player, gift.priceMoney);
                }

                if (gift.pricePoints > 0) {
                    boolean enoughPoints = hasEnoughPoints(player, gift.pricePoints);
                    if (!enoughPoints) {
                        if (economy != null && gift.priceMoney > 0) {
                            economy.depositPlayer(player, gift.priceMoney);
                        }
                        return new BuyGiftResult(false, BuyGiftResultType.INSUFFICIENT_POINTS);
                    }
                    takePoints(player, gift.pricePoints);
                }

                plugin.getDatabaseManager().addPurchasedGift(player.getUniqueId(), giftId).join();
                plugin.getDatabaseManager().setGiftPurchaseTime(player.getUniqueId(), System.currentTimeMillis()).join();
                switchGift(player, giftId).join();

                return new BuyGiftResult(true, BuyGiftResultType.SUCCESS);
            });
        });
    }

    public CompletableFuture<Boolean> checkGiftExpiration(Player player) {
        return CompletableFuture.supplyAsync(() -> {
            return plugin.getDatabaseManager().getPlayerData(player.getUniqueId()).thenApply(playerData -> {
                if (playerData == null || playerData.giftId == null) {
                    return false;
                }

                GiftConfig gift = gifts.get(playerData.giftId);
                if (gift == null || gift.durationDays <= 0) {
                    return false;
                }

                long currentTime = System.currentTimeMillis();
                long purchaseTime = playerData.giftPurchaseTime;
                if (purchaseTime <= 0) {
                    return false;
                }

                long durationMillis = (long) gift.durationDays * 24 * 60 * 60 * 1000;
                if (currentTime - purchaseTime > durationMillis) {
                    plugin.getDatabaseManager().getPurchasedGifts(player.getUniqueId()).thenAccept(purchasedGifts -> {
                        if (purchasedGifts != null && !purchasedGifts.isEmpty()) {
                            int currentIndex = giftOrder.indexOf(playerData.giftId);
                            if (currentIndex > 0) {
                                for (int i = currentIndex - 1; i >= 0; i--) {
                                    String giftId = giftOrder.get(i);
                                    if (purchasedGifts.contains(giftId)) {
                                        switchGift(player, giftId);
                                        return;
                                    }
                                }
                            }
                            String defaultGiftId = plugin.getConfigManager().getConfig()
                                .getString("new_player_reward.default_gift_id", "default");
                            switchGift(player, defaultGiftId);
                        } else {
                            String defaultGiftId = plugin.getConfigManager().getConfig()
                                .getString("new_player_reward.default_gift_id", "default");
                            switchGift(player, defaultGiftId);
                        }
                    });
                    return true;
                }

                return false;
            }).join();
        });
    }

    public CompletableFuture<Integer> getGiftRemainingDays(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            return plugin.getDatabaseManager().getPlayerData(uuid).thenApply(playerData -> {
                if (playerData == null || playerData.giftId == null || playerData.giftPurchaseTime <= 0) {
                    return -1;
                }

                GiftConfig gift = gifts.get(playerData.giftId);
                if (gift == null || gift.durationDays <= 0) {
                    return -1;
                }

                long currentTime = System.currentTimeMillis();
                long purchaseTime = playerData.giftPurchaseTime;
                long durationMillis = (long) gift.durationDays * 24 * 60 * 60 * 1000;
                long remainingMillis = durationMillis - (currentTime - purchaseTime);

                if (remainingMillis <= 0) {
                    return 0;
                }

                return (int) (remainingMillis / (24 * 60 * 60 * 1000));
            }).join();
        });
    }

    private boolean hasEnoughPoints(Player player, int amount) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "NONE");
        if ("NONE".equals(pointsType)) return true;

        switch (pointsType) {
            case "PLAYERPOINTS" -> {
                try {
                    Object api = getPlayerPointsAPI();
                    if (api == null) {
                        return false;
                    }

                    int playerPoints = getPlayerPoints(api, player);
                    return playerPoints >= amount;
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints 检查失败: " + e.getClass().getName() + ": " + e.getMessage());
                    return false;
                }
            }
            case "CUSTOM" -> {
                return true;
            }
            default -> {
                plugin.getLogger().warning("未知的点券类型: " + pointsType + "，请检查配置文件中的 economy.points_type");
                return false;
            }
        }
    }

    private int getPlayerPoints(Object api, Player player) {
        try {
            Method lookMethod = api.getClass().getMethod("look", UUID.class);
            Object result = lookMethod.invoke(api, player.getUniqueId());
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
            return 0;
        } catch (Exception e) {
            plugin.getLogger().warning("获取玩家点券失败: " + e.getClass().getName() + ": " + e.getMessage());
            return 0;
        }
    }

    private void takePoints(Player player, int amount) {
        String pointsType = plugin.getConfigManager().getConfig()
            .getString("economy.points_type", "NONE");
        if ("NONE".equals(pointsType)) return;

        switch (pointsType) {
            case "CUSTOM" -> {
                String takeCmd = plugin.getConfigManager().getConfig()
                    .getString("economy.points_command.take", "")
                    .replace("%player%", player.getName())
                    .replace("%amount%", String.valueOf(amount));
                SchedulerUtils.runTask(plugin, () ->
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), takeCmd));
            }
            case "PLAYERPOINTS" -> {
                try {
                    Object api = getPlayerPointsAPI();
                    if (api == null) return;
                    Method takeMethod = api.getClass().getMethod("take", UUID.class, int.class);
                    takeMethod.invoke(api, player.getUniqueId(), amount);
                } catch (Exception e) {
                    plugin.getLogger().warning("PlayerPoints 扣除失败: " + e.getMessage());
                }
            }
        }
    }

    private Object getPlayerPointsAPI() {
        try {
            Class<?> ppClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
            Object ppInstance = ppClass.getMethod("getInstance").invoke(null);
            if (ppInstance == null) {
                plugin.getLogger().warning("PlayerPoints.getInstance() 返回 null！");
                plugin.getLogger().warning("PlayerPoints 插件是否正确加载？");
                return null;
            }
            Object api = ppClass.getMethod("getAPI").invoke(ppInstance);
            if (api == null) {
                plugin.getLogger().warning("PlayerPoints.getAPI() 返回 null！");
                return null;
            }
            return api;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("找不到 PlayerPoints 类！请确保 PlayerPoints 插件已安装。");
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("获取 PlayerPoints API 失败: " + e.getMessage());
            return null;
        }
    }
}
