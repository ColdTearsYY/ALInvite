package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.utils.ItemUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 礼包管理：配置加载、购买/切换、新手礼包发放。
 * 购买链路无任何 join：经济操作经调度器切实体线程，DB 走 IO 线程池。
 */
public class GiftManager {

    private final ALInvite plugin;
    private final Map<String, GiftConfig> gifts = new LinkedHashMap<>();
    private final List<String> giftOrder = new ArrayList<>();

    public GiftManager(ALInvite plugin) {
        this.plugin = plugin;
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
        if (giftsConfig == null) {
            return;
        }

        gifts.clear();
        giftOrder.clear();

        for (String giftId : giftsConfig.getKeys(false)) {
            ConfigurationSection giftConfig = giftsConfig.getConfigurationSection(giftId);
            if (giftConfig != null) {
                String name = giftConfig.getString("name", "礼包");
                List<MilestoneManager.Reward> rewards = loadRewards("gift_shop.gifts." + giftId + ".rewards");
                Material material = ItemUtil.parseMaterial(giftConfig.getString("material", "CHEST"), Material.CHEST,
                    warning -> plugin.getLogger().warning("[gift_shop.gifts." + giftId + "] " + warning));
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

    public GiftConfig getGift(String giftId) {
        return gifts.get(giftId);
    }

    public Map<String, GiftConfig> getGifts() {
        return gifts;
    }

    public Map<String, GiftConfig> getAllGifts() {
        return gifts;
    }

    public List<String> getGiftOrder() {
        return giftOrder;
    }

    // ─── 购买/切换 ───

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
        FAILED,
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

    /**
     * 购买礼包：权限与经济操作在玩家实体线程执行，DB 写入走 IO 线程池，全程无 join。
     * 点券不足时退还已扣的金币（与旧行为一致）。
     */
    public CompletableFuture<BuyGiftResult> buyGift(Player player, String giftId) {
        GiftConfig gift = gifts.get(giftId);
        if (gift == null) {
            return CompletableFuture.completedFuture(new BuyGiftResult(false, BuyGiftResultType.NOT_FOUND));
        }

        String veteranPermission = plugin.getConfigManager().getConfig()
            .getString("invite_code.veteran_permission", "alinvite.veteran");

        return plugin.getScheduler().supplyAtPlayer(player, () -> {
            if (!player.hasPermission(veteranPermission)) {
                return new BuyGiftResult(false, BuyGiftResultType.NO_PERMISSION);
            }

            boolean vaultAvailable = com.alinvite.utils.VaultEconomyUtils.isAvailable(plugin);
            boolean moneyWithdrawn = false;
            if (vaultAvailable && gift.priceMoney > 0) {
                if (!com.alinvite.utils.VaultEconomyUtils.has(plugin, player, gift.priceMoney)) {
                    return new BuyGiftResult(false, BuyGiftResultType.INSUFFICIENT_MONEY);
                }
                com.alinvite.utils.VaultEconomyUtils.withdraw(plugin, player, gift.priceMoney);
                moneyWithdrawn = true;
            }

            if (gift.pricePoints > 0) {
                if (!plugin.getRewardService().hasEnoughPoints(player, gift.pricePoints)) {
                    if (moneyWithdrawn) {
                        com.alinvite.utils.VaultEconomyUtils.deposit(plugin, player, gift.priceMoney);
                    }
                    return new BuyGiftResult(false, BuyGiftResultType.INSUFFICIENT_POINTS);
                }
                plugin.getRewardService().takePoints(player, gift.pricePoints);
            }
            return new BuyGiftResult(true, BuyGiftResultType.SUCCESS);
        }).thenCompose(economyResult -> {
            if (!economyResult.success) {
                return CompletableFuture.completedFuture(economyResult);
            }
            long now = System.currentTimeMillis();
            return plugin.getDatabaseManager().addPurchasedGift(player.getUniqueId(), giftId)
                .thenCompose(v -> plugin.getDatabaseManager().setGiftPurchaseTime(player.getUniqueId(), now))
                .thenCompose(v -> switchGift(player, giftId))
                .thenApply(v -> new BuyGiftResult(true, BuyGiftResultType.SUCCESS))
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("礼包购买数据写入失败 (" + giftId + "): " + throwable.getMessage());
                    return new BuyGiftResult(false, BuyGiftResultType.FAILED);
                });
        });
    }

    /**
     * GUI 购买/切换礼包入口（动作串 buy_gift:<giftId>，按 ID 定位，杜绝槽位换算错位）。
     */
    public void purchaseFromMenu(Player player, String giftId, boolean reopenShopAfterBuy) {
        GiftConfig gift = getGift(giftId);
        if (gift == null) {
            player.sendMessage(com.alinvite.config.ConfigManager.colorize("&c礼包不存在！"));
            return;
        }

        plugin.getDatabaseManager().getGiftId(player.getUniqueId()).thenCompose(currentGiftId -> {
            if (giftId.equals(currentGiftId)) {
                plugin.getScheduler().runAtPlayer(player, () ->
                    player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.already_active", player)
                        .replace("{gift_name}", com.alinvite.config.ConfigManager.colorize(gift.name))));
                return CompletableFuture.<Void>completedFuture(null);
            }

            return plugin.getDatabaseManager().getPurchasedGifts(player.getUniqueId()).thenCompose(purchased -> {
                if (purchased.contains(giftId)) {
                    return switchGift(player, giftId).thenRun(() -> plugin.getScheduler().runAtPlayer(player, () -> {
                        player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.switch_success", player)
                            .replace("{gift_name}", com.alinvite.config.ConfigManager.colorize(gift.name)));
                        plugin.getMenuManager().openVeteranMenu(player);
                    }));
                }

                return buyGift(player, giftId).thenAccept(result -> plugin.getScheduler().runAtPlayer(player, () -> {
                    if (result.success) {
                        player.sendMessage(plugin.getConfigManager().getMessage("commands.buygift.success", player)
                            .replace("{gift_name}", com.alinvite.config.ConfigManager.colorize(gift.name)));
                        if (reopenShopAfterBuy) {
                            plugin.getMenuManager().openShopMenu(player);
                        }
                    } else {
                        String message = switch (result.type) {
                            case INSUFFICIENT_MONEY -> plugin.getConfigManager()
                                .getMessage("commands.buygift.fail_money", player).replace("{price}", String.valueOf(gift.priceMoney));
                            case INSUFFICIENT_POINTS -> plugin.getConfigManager()
                                .getMessage("commands.buygift.fail_points", player).replace("{price}", String.valueOf(gift.pricePoints));
                            case NO_PERMISSION -> plugin.getConfigManager().getMessage("commands.buygift.no_permission", player);
                            case NOT_FOUND -> "&c礼包不存在！";
                            default -> "&c购买失败";
                        };
                        player.sendMessage(com.alinvite.config.ConfigManager.colorize(message));
                    }
                }));
            });
        });
    }

    // ─── 新手礼包 ───

    /**
     * 检查并处理过期礼包：过期后回退到最近购买的仍有效礼包或默认礼包。
     * 全链无 join；返回 true 表示发生了过期切换。
     */
    public CompletableFuture<Boolean> checkGiftExpiration(Player player) {
        UUID uuid = player.getUniqueId();
        return plugin.getDatabaseManager().getPlayerData(uuid).thenCompose(playerData -> {
            if (playerData == null || playerData.giftId == null || playerData.giftPurchaseTime <= 0) {
                return CompletableFuture.completedFuture(false);
            }
            GiftConfig gift = gifts.get(playerData.giftId);
            if (gift == null || gift.durationDays <= 0) {
                return CompletableFuture.completedFuture(false);
            }
            long durationMillis = gift.durationDays * 24L * 60L * 60L * 1000L;
            if (System.currentTimeMillis() - playerData.giftPurchaseTime <= durationMillis) {
                return CompletableFuture.completedFuture(false);
            }

            return plugin.getDatabaseManager().getPurchasedGifts(uuid).thenCompose(purchased -> {
                String target = null;
                int currentIndex = giftOrder.indexOf(playerData.giftId);
                if (currentIndex > 0) {
                    for (int i = currentIndex - 1; i >= 0; i--) {
                        String candidate = giftOrder.get(i);
                        if (purchased.contains(candidate)) {
                            target = candidate;
                            break;
                        }
                    }
                }
                if (target == null) {
                    target = plugin.getConfigManager().getConfig()
                        .getString("new_player_reward.default_gift_id", "default");
                }
                return switchGift(player, target).thenApply(v -> true);
            });
        });
    }

    /** 礼包剩余天数（无期限返回 -1，已过期返回 0）。 */
    public CompletableFuture<Integer> getGiftRemainingDays(UUID uuid) {
        return plugin.getDatabaseManager().getPlayerData(uuid).thenApply(playerData -> {
            if (playerData == null || playerData.giftId == null || playerData.giftPurchaseTime <= 0) {
                return -1;
            }
            GiftConfig gift = gifts.get(playerData.giftId);
            if (gift == null || gift.durationDays <= 0) {
                return -1;
            }
            long durationMillis = gift.durationDays * 24L * 60L * 60L * 1000L;
            long remaining = durationMillis - (System.currentTimeMillis() - playerData.giftPurchaseTime);
            if (remaining <= 0) {
                return 0;
            }
            return (int) (remaining / (24L * 60L * 60L * 1000L));
        });
    }

    /**
     * 新玩家绑定邀请码后按邀请人礼包发奖。可在任意线程调用；
     * 奖励发放由 RewardService 切回实体线程。
     */
    public void giveGiftRewards(Player player, UUID inviterUuid) {
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

                plugin.getRewardService().giveRewards(player, gift.rewards);
            });
        });
    }
}
