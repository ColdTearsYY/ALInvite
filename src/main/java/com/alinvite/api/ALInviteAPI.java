package com.alinvite.api;

import com.alinvite.ALInvite;
import com.alinvite.manager.GiftManager;
import com.alinvite.manager.MilestoneManager;
import com.alinvite.manager.PointsRebateManager;
import com.alinvite.manager.LeaderboardManager;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ALInviteAPI {

    private static ALInvite plugin;

    private ALInviteAPI() {}

    /** 获取插件实例。 */
    public static ALInvite getPlugin() {
        return plugin;
    }

    public static void init(ALInvite plugin) {
        ALInviteAPI.plugin = plugin;
    }

    public static CompletableFuture<String> getInviteCode(Player player) {
        return getInviteCode(player.getUniqueId());
    }

    public static CompletableFuture<String> getInviteCode(UUID uuid) {
        return AsyncPool.supply(() -> plugin.getCacheManager().getInviteCode(uuid,
                plugin.getDatabaseManager()::getInviteCodeByPlayerSync));
    }

    public static CompletableFuture<Integer> getTotalInvites(Player player) {
        return getTotalInvites(player.getUniqueId());
    }

    public static CompletableFuture<Integer> getTotalInvites(UUID uuid) {
        return AsyncPool.supply(() -> {
            Integer total = plugin.getCacheManager().getStats(uuid, id -> {
                var data = plugin.getDatabaseManager().getPlayerDataSync(id);
                return data != null ? data.totalInvites : 0;
            });
            return total == null ? 0 : total;
        });
    }

    public static CompletableFuture<String> getPurchasedGift(Player player) {
        return getPurchasedGift(player.getUniqueId());
    }

    public static CompletableFuture<String> getPurchasedGift(UUID uuid) {
        return AsyncPool.supply(() -> plugin.getCacheManager().getGiftId(uuid,
                plugin.getDatabaseManager()::getGiftIdSync));
    }

    public static CompletableFuture<GiftManager.GiftConfig> getActiveGift(Player player) {
        return getActiveGift(player.getUniqueId());
    }

    public static CompletableFuture<GiftManager.GiftConfig> getActiveGift(UUID uuid) {
        return getPurchasedGift(uuid).thenApply(giftId -> {
            if (giftId == null) {
                boolean requireGift = plugin.getConfigManager().getConfig()
                    .getBoolean("new_player_reward.require_gift", false);
                if (requireGift) {
                    return null;
                }
                giftId = plugin.getConfigManager().getConfig()
                    .getString("new_player_reward.default_gift_id", "default");
            }

            return plugin.getGiftManager().getGift(giftId);
        });
    }

    public static CompletableFuture<Boolean> isSameIp(Player p1, Player p2) {
        return AsyncPool.supply(() -> {
            if (p1 == null || p2 == null) {
                return false;
            }
            String ip1 = p1.getAddress().getAddress().getHostAddress();
            String ip2 = p2.getAddress().getAddress().getHostAddress();
            return ip1 != null && ip1.equals(ip2);
        });
    }

    public static CompletableFuture<List<UUID>> getInvitees(UUID inviterUuid) {
        return AsyncPool.supply(() -> {
            List<UUID> result = new ArrayList<>();
            return result;
        });
    }

    public static CompletableFuture<Boolean> isPlayerBound(Player player) {
        return isPlayerBound(player.getUniqueId());
    }

    public static CompletableFuture<Boolean> isPlayerBound(UUID uuid) {
        return plugin.getDatabaseManager().getInviter(uuid)
            .thenApply(inviterUuid -> inviterUuid != null);
    }

    public static CompletableFuture<String> getBindStatus(Player player) {
        return getBindStatus(player.getUniqueId());
    }

    public static CompletableFuture<String> getBindStatus(UUID uuid) {
        return isPlayerBound(uuid)
            .thenApply(bound -> bound ? "已绑定" : "未绑定");
    }

    public static CompletableFuture<String> getInviterName(Player player) {
        return getInviterName(player.getUniqueId());
    }

    public static CompletableFuture<String> getInviterName(UUID uuid) {
        return plugin.getDatabaseManager().getInviter(uuid)
            .thenCompose(inviterUuid -> {
                if (inviterUuid == null) {
                    return CompletableFuture.completedFuture("无");
                }
                return plugin.getDatabaseManager().getPlayerData(inviterUuid)
                    .thenApply(data -> {
                        if (data == null) {
                            return "无";
                        }
                        Player onlineInviter = plugin.getServer().getPlayer(inviterUuid);
                        if (onlineInviter != null && onlineInviter.isOnline()) {
                            return onlineInviter.getName();
                        }
                        return data.inviteCode != null ? data.inviteCode : "未知";
                    });
            });
    }

    public static CompletableFuture<UUID> getInviterUuid(Player player) {
        return getInviterUuid(player.getUniqueId());
    }

    public static CompletableFuture<UUID> getInviterUuid(UUID uuid) {
        return plugin.getDatabaseManager().getInviter(uuid);
    }

    public static CompletableFuture<String> resolvePlaceholders(String text, Player player) {
        if (text == null || text.isEmpty()) {
            return CompletableFuture.completedFuture(text);
        }
        return plugin.getMenuManager().getPlaceholderResolver().applyPlaceholdersAsync(text, player);
    }

    private static final java.util.List<InviteSuccessListener> SUCCESS_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 注册邀请成功监听器（回调在全局线程触发）。 */
    public static void registerInviteListener(InviteSuccessListener listener) {
        if (listener != null && !SUCCESS_LISTENERS.contains(listener)) {
            SUCCESS_LISTENERS.add(listener);
        }
    }

    /** 内部使用：邀请成功时分发到已注册监听器。 */
    public static void fireInviteSuccess(UUID inviterUuid, UUID inviteeUuid) {
        for (InviteSuccessListener listener : SUCCESS_LISTENERS) {
            try {
                listener.onInviteSuccess(inviterUuid, inviteeUuid);
            } catch (Exception e) {
                plugin.getLogger().warning("InviteSuccessListener 回调异常: " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface InviteSuccessListener {
        void onInviteSuccess(UUID inviterUuid, UUID inviteeUuid);
    }

    // ==================== 点券充值返点系统 API ====================

    /**
     * 处理点券充值返点
     * 通过API调用触发返点处理
     * 
     * @param targetPlayer 目标玩家名称（接收点券的玩家）
     * @param amount 充值点券数量
     * @return CompletableFuture<Boolean> 处理结果，true表示成功
     */
    public static CompletableFuture<Boolean> processPointsRecharge(String targetPlayer, double amount) {
        return plugin.getPointsRebateManager().processRecharge("ThirdParty", targetPlayer, amount, false);
    }

    /**
     * 处理点券充值返点（带操作者信息）
     * 适用于管理员命令调用
     * 
     * @param operator 操作者名称（执行命令的玩家或控制台）
     * @param targetPlayer 目标玩家名称（接收点券的玩家）
     * @param amount 充值点券数量
     * @param skipRebate 是否跳过返点处理（用于测试或特殊情况）
     * @return CompletableFuture<Boolean> 处理结果，true表示成功
     */
    public static CompletableFuture<Boolean> processPointsRecharge(
            String operator, String targetPlayer, double amount, boolean skipRebate) {
        return plugin.getPointsRebateManager().processRecharge(operator, targetPlayer, amount, skipRebate);
    }

    /**
     * 带流水键的充值处理：相同 transactionKey 的重复推送只会发放一次返利
     * （流水占用记录在 points_rebate 表，跨服务器共享数据库时同样生效）。
     */
    public static CompletableFuture<Boolean> processPointsRecharge(
            String operator, String targetPlayer, double amount, boolean skipRebate, String transactionKey) {
        return plugin.getPointsRebateManager().processRecharge(operator, targetPlayer, amount, skipRebate, transactionKey);
    }

    /**
     * 获取玩家累计返点总额
     * @param player 玩家对象
     * @return CompletableFuture<Double> 累计返点总额
     */
    public static CompletableFuture<Double> getTotalRebateAmount(Player player) {
        return getTotalRebateAmount(player.getUniqueId());
    }

    /**
     * 获取玩家累计返点总额
     * @param uuid 玩家UUID
     * @return CompletableFuture<Double> 累计返点总额
     */
    public static CompletableFuture<Double> getTotalRebateAmount(UUID uuid) {
        return plugin.getDatabaseManager().getTotalRebateAmount(uuid);
    }

    /**
     * 检查跨服重复交易
     * @param playerUuid 玩家UUID
     * @param amount 充值金额
     * @return CompletableFuture<Boolean> 是否为重复交易
     */
    public static CompletableFuture<Boolean> checkRebateDuplicate(UUID playerUuid, double amount) {
        return plugin.getDatabaseManager().checkRebateDuplicate(playerUuid, amount);
    }

    // ==================== 数据查询扩展（2.0.0 新增） ====================

    /** 获取玩家未领取的返点数量（手动领取制下的待领取池）。 */
    public static CompletableFuture<Double> getUnclaimedRebate(UUID uuid) {
        return plugin.getDatabaseManager().getUnclaimedRebate(uuid);
    }

    /** 获取玩家贡献返点余额（别名，与 getTotalRebateAmount 语义区分）。 */
    public static CompletableFuture<Double> getContribution(UUID uuid) {
        return plugin.getDatabaseManager().getContributionAmount(uuid);
    }

    /** 获取已领取的里程碑所需人数集合（如 [1, 5, 10]）。 */
    public static CompletableFuture<java.util.Set<Integer>> getClaimedMilestones(UUID uuid) {
        return AsyncPool.supply(() -> {
            String raw = plugin.getDatabaseManager().getClaimedMilestonesSync(uuid);
            java.util.Set<Integer> result = new java.util.HashSet<>();
            if (raw == null || raw.isBlank() || raw.equals("[]")) {
                return result;
            }
            for (String part : raw.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    result.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException ignored) {
                }
            }
            return result;
        });
    }

    /** 查询指定里程碑是否已领取。 */
    public static CompletableFuture<Boolean> hasClaimedMilestone(UUID uuid, int required) {
        return getClaimedMilestones(uuid).thenApply(set -> set.contains(required));
    }

    /** 获取待领取里程碑列表（邀请人离线期间达成的）。 */
    public static CompletableFuture<List<String>> getPendingMilestones(UUID uuid) {
        return plugin.getDatabaseManager().getPendingMilestones(uuid);
    }

    /** 返利记录条目。 */
    public record RebateEntry(long time, double amount, String sourcePlayer) {
    }

    /** 获取最近的返利记录（时间倒序）。 */
    public static CompletableFuture<List<RebateEntry>> getRebateRecords(UUID uuid, int limit) {
        return plugin.getDatabaseManager().getRebateRecords(uuid, limit).thenApply(list -> {
            List<RebateEntry> result = new ArrayList<>();
            for (var r : list) {
                result.add(new RebateEntry(r.createdAt(), r.amount(), r.sourceName()));
            }
            return result;
        });
    }

    /** 获取礼包剩余天数（无期限返回 -1，已过期返回 0）。 */
    public static CompletableFuture<Integer> getGiftRemainingDays(UUID uuid) {
        return plugin.getGiftManager().getGiftRemainingDays(uuid);
    }

    /** 获取礼包状态文本：未购买 / 已购买 / 已过期 / 永久。 */
    public static CompletableFuture<String> getGiftStatus(UUID uuid) {
        return plugin.getPlaceholderResolver().getGiftStatusAsync(uuid);
    }

    /** 获取下一个未达成里程碑的所需人数（全部达成返回 "MAX"）。 */
    public static CompletableFuture<String> getNextMilestone(UUID uuid) {
        return plugin.getPlaceholderResolver().getNextMilestoneAsync(uuid);
    }

    /** 获取下一个未达成里程碑的名称（全部达成返回 "MAX"）。 */
    public static CompletableFuture<String> getNextMilestoneName(UUID uuid) {
        return plugin.getPlaceholderResolver().getNextMilestoneAsync(uuid).thenApply(num -> {
            if ("MAX".equals(num)) {
                return "MAX";
            }
            try {
                var milestone = plugin.getMilestoneManager().getMilestone(Integer.parseInt(num));
                return milestone != null ? milestone.name : num;
            } catch (NumberFormatException e) {
                return num;
            }
        });
    }

    /**
     * 获取玩家当前充值返点比例（如 0.15 表示 15%）。
     * 返回负值表示贡献模式（只记录不即时发放点券），绝对值为比例。
     * 无匹配权限组时返回基础比例。
     */
    public static double getRebateRate(Player player) {
        return plugin.getPointsRebateManager().getRebateRate(player);
    }

    /** 返点比例的展示文本（如 "15%" / "20%（贡献模式）"）。 */
    public static String getRebateRateDisplay(Player player) {
        return plugin.getPointsRebateManager().getRebateRateDisplay(player);
    }

    // ==================== 服务器标识（2.0.0 新增） ====================

    /** 本服唯一 ID（config.yml serverid）。 */
    public static String getServerId() {
        return plugin.getConfigManager().getServerId();
    }

    /** 服务器别称（config.yml serverName）。 */
    public static String getServerAlias() {
        return plugin.getConfigManager().getServerAlias();
    }

    // ==================== 排行榜系统 API ====================

    /**
     * 获取排行榜类型枚举
     * @param key 类型键值
     * @return LeaderboardType 排行榜类型
     */
    public static LeaderboardManager.LeaderboardType getLeaderboardType(String key) {
        return LeaderboardManager.LeaderboardType.fromKey(key);
    }

    /**
     * 获取排行榜变量
     * 用于其他插件获取排行榜数据变量
     * 
     * @param type 排行榜类型
     * @return Map<String, String> 变量映射
     */
    public static java.util.Map<String, String> getLeaderboardVariables(LeaderboardManager.LeaderboardType type) {
        return plugin.getLeaderboardManager().getLeaderboardVariables(type);
    }

    /**
     * 获取排行榜变量（通过字符串键值）
     * 用于其他插件获取排行榜数据变量
     * 
     * @param typeKey 排行榜类型键值
     * @return Map<String, String> 变量映射
     */
    public static java.util.Map<String, String> getLeaderboardVariables(String typeKey) {
        LeaderboardManager.LeaderboardType type = getLeaderboardType(typeKey);
        if (type == null) {
            return new java.util.HashMap<>();
        }
        return getLeaderboardVariables(type);
    }

    /**
     * 替换字符串中的排行榜变量
     * 用于其他插件处理包含排行榜变量的文本
     * 
     * @param input 输入文本
     * @param type 排行榜类型
     * @return String 替换后的文本
     */
    public static String replaceLeaderboardVariables(String input, LeaderboardManager.LeaderboardType type) {
        return plugin.getLeaderboardManager().replaceLeaderboardVariables(input, type);
    }

    /**
     * 替换字符串中的排行榜变量（通过字符串键值）
     * 用于其他插件处理包含排行榜变量的文本
     * 
     * @param input 输入文本
     * @param typeKey 排行榜类型键值
     * @return String 替换后的文本
     */
    public static String replaceLeaderboardVariables(String input, String typeKey) {
        LeaderboardManager.LeaderboardType type = getLeaderboardType(typeKey);
        if (type == null) {
            return input;
        }
        return replaceLeaderboardVariables(input, type);
    }

    /**
     * 手动更新排行榜数据
     * 用于其他插件在需要时触发排行榜更新
     * 
     * @param type 排行榜类型
     */
    public static void updateLeaderboard(LeaderboardManager.LeaderboardType type) {
        plugin.getLeaderboardManager().updateLeaderboard(type);
    }

    /**
     * 手动更新排行榜数据（通过字符串键值）
     * 用于其他插件在需要时触发排行榜更新
     * 
     * @param typeKey 排行榜类型键值
     */
    public static void updateLeaderboard(String typeKey) {
        LeaderboardManager.LeaderboardType type = getLeaderboardType(typeKey);
        if (type != null) {
            updateLeaderboard(type);
        }
    }
}
