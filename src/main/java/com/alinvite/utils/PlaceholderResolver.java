package com.alinvite.utils;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.gui.render.RenderContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 占位符解析器。
 *
 * 性能约定：renderContext(player) 只允许在 IO 线程调用（内部走 CacheManager 缓存，
 * 未命中才回源数据库）；渲染阶段拿到上下文后只做内存替换，绝不查库。
 * 旧版同步 applyPlaceholders 保留 API 兼容，内部同样走缓存（未命中回源一次）。
 */
public class PlaceholderResolver {

    private final ALInvite plugin;
    private final Map<String, String> customReplacements;

    public PlaceholderResolver(ALInvite plugin) {
        this.plugin = plugin;
        this.customReplacements = new HashMap<>();
    }

    public void registerCustomReplacement(String placeholder, String value) {
        customReplacements.put(placeholder, value);
    }

    public void unregisterCustomReplacement(String placeholder) {
        customReplacements.remove(placeholder);
    }

    // ─── 渲染上下文 ───

    /** 仅在 IO 线程调用（AsyncPool / scheduler.runAsync 内部）。 */
    public RenderContext renderContext(Player player) {
        RenderContext context = new RenderContext(player);
        context.add("invite_code", getInviteCodeSync(player.getUniqueId()));
        context.add("bind_status", getBindStatusSync(player.getUniqueId()));
        context.add("inviter_name", getInviterNameSync(player.getUniqueId()));
        context.add("total_invites", getTotalInvitesSync(player.getUniqueId()));
        context.add("gift_name", getGiftNameSync(player.getUniqueId()));
        context.add("has_gift", hasActiveGiftSync(player.getUniqueId()));
        context.add("gift_status", getGiftStatusSync(player.getUniqueId()));
        context.add("gift_remaining_days", getGiftRemainingDaysSync(player.getUniqueId()));
        context.add("next_milestone", getNextMilestoneSync(player.getUniqueId()));
        context.add("total_rebate", getTotalRebateSync(player.getUniqueId()));
        context.add("contribution", getContributionSync(player.getUniqueId()));
        context.add("unclaimed_rebate", formatAmount(plugin.getDatabaseManager().getUnclaimedRebateSync(player.getUniqueId())));
        context.add("rebate_rate", plugin.getPointsRebateManager().getRebateRateDisplay(player));
        customReplacements.forEach(context::add);
        return context;
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) && !Double.isInfinite(amount)
            ? String.valueOf((long) amount)
            : String.format(java.util.Locale.ROOT, "%.2f", amount);
    }

    public CompletableFuture<RenderContext> renderContextAsync(Player player) {
        return AsyncPool.supply(() -> renderContext(player));
    }

    /** 旧 API 兼容：键为 {x} 与 %alinvite_x% 两套写法。 */
    public CompletableFuture<Map<String, String>> resolveAllPlaceholders(Player player) {
        return renderContextAsync(player).thenApply(context -> {
            Map<String, String> placeholders = new HashMap<>();
            for (Map.Entry<String, String> entry : context.strings().entrySet()) {
                String key = entry.getKey();
                placeholders.put("{" + key + "}", entry.getValue());
                placeholders.put("%alinvite_" + key + "%", entry.getValue());
            }
            placeholders.put("%alinvite_code%", context.strings().get("invite_code"));
            return placeholders;
        });
    }

    // ─── 同步取值（缓存回源，热路径请改用 renderContext） ───

    public String getInviteCodeSync(UUID uuid) {
        String code = plugin.getCacheManager().getInviteCode(uuid, plugin.getDatabaseManager()::getInviteCodeByPlayerSync);
        if (code != null && (code.isEmpty() || "null".equalsIgnoreCase(code))) {
            code = null;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        boolean hasPermission = player != null && player.hasPermission(
            plugin.getConfigManager().getConfig().getString("invite_code.veteran_permission", "alinvite.veteran"));
        if (!hasPermission) {
            return "未解锁";
        }
        return code != null ? code : "生成中...";
    }

    public String getBindStatusSync(UUID uuid) {
        UUID inviter = plugin.getCacheManager().getInviter(uuid, plugin.getDatabaseManager()::getInviterSync);
        return inviter != null ? "已绑定" : "未绑定";
    }

    public String getInviterNameSync(UUID uuid) {
        UUID inviter = plugin.getCacheManager().getInviter(uuid, plugin.getDatabaseManager()::getInviterSync);
        if (inviter == null) {
            return "无";
        }
        var data = plugin.getDatabaseManager().getPlayerDataSync(inviter);
        if (data == null) {
            return "无";
        }
        Player onlineInviter = plugin.getServer().getPlayer(inviter);
        if (onlineInviter != null && onlineInviter.isOnline()) {
            return onlineInviter.getName();
        }
        return data.inviteCode != null && !data.inviteCode.isEmpty() ? data.inviteCode : "未知";
    }

    public String getTotalInvitesSync(UUID uuid) {
        Integer total = plugin.getCacheManager().getStats(uuid, id -> {
            var data = plugin.getDatabaseManager().getPlayerDataSync(id);
            return data != null ? data.totalInvites : 0;
        });
        return String.valueOf(total == null ? 0 : total);
    }

    public String getGiftNameSync(UUID uuid) {
        String giftId = resolveEffectiveGiftId(uuid);
        var gift = plugin.getGiftManager().getGift(giftId);
        return gift != null ? ConfigManager.colorize(gift.name) : "无";
    }

    public String hasActiveGiftSync(UUID uuid) {
        String giftId = plugin.getCacheManager().getGiftId(uuid, plugin.getDatabaseManager()::getGiftIdSync);
        return giftId != null ? "true" : "false";
    }

    public String getGiftStatusSync(UUID uuid) {
        String giftId = plugin.getCacheManager().getGiftId(uuid, plugin.getDatabaseManager()::getGiftIdSync);
        if (giftId == null) {
            return "未购买";
        }
        long purchaseTime = plugin.getCacheManager().getGiftPurchaseTime(uuid,
                id -> plugin.getDatabaseManager().getGiftPurchaseTimeSync(id));
        if (purchaseTime == 0) {
            return "未购买";
        }
        var gift = plugin.getGiftManager().getGift(giftId);
        if (gift == null) {
            return "未购买";
        }
        if (gift.durationDays == 0) {
            return "已购买";
        }
        long expirationTime = purchaseTime + gift.durationDays * 24L * 60L * 60L * 1000L;
        return System.currentTimeMillis() > expirationTime ? "已过期" : "已购买";
    }

    public String getGiftRemainingDaysSync(UUID uuid) {
        String giftId = plugin.getCacheManager().getGiftId(uuid, plugin.getDatabaseManager()::getGiftIdSync);
        if (giftId == null) {
            return "未购买";
        }
        long purchaseTime = plugin.getCacheManager().getGiftPurchaseTime(uuid,
                id -> plugin.getDatabaseManager().getGiftPurchaseTimeSync(id));
        if (purchaseTime == 0) {
            return "未购买";
        }
        var gift = plugin.getGiftManager().getGift(giftId);
        if (gift == null) {
            return "未购买";
        }
        if (gift.durationDays == 0) {
            return "永久";
        }
        long expirationTime = purchaseTime + gift.durationDays * 24L * 60L * 60L * 1000L;
        long remaining = expirationTime - System.currentTimeMillis();
        if (remaining <= 0) {
            return "0天";
        }
        return (remaining / (24L * 60L * 60L * 1000L)) + "天";
    }

    public String getNextMilestoneSync(UUID uuid) {
        var milestones = plugin.getMilestoneManager().getMilestones();
        Integer currentTotal = plugin.getCacheManager().getStats(uuid, id -> {
            var data = plugin.getDatabaseManager().getPlayerDataSync(id);
            return data != null ? data.totalInvites : 0;
        });
        for (var entry : milestones.entrySet()) {
            if (entry.getKey() > currentTotal) {
                return String.valueOf(entry.getKey());
            }
        }
        return "MAX";
    }

    public String getTotalRebateSync(UUID uuid) {
        try {
            double totalRebate = plugin.getCacheManager().getTotalRebate(uuid,
                    plugin.getDatabaseManager()::getTotalRebateAmountSync);
            if (Double.isNaN(totalRebate)) {
                return "0.00";
            }
            return String.format("%.2f", totalRebate);
        } catch (Exception e) {
            plugin.getLogger().warning("获取累计返点总额失败: " + e.getMessage());
            return "0.00";
        }
    }

    public String getContributionSync(UUID uuid) {
        try {
            double contribution = plugin.getCacheManager().getContribution(uuid,
                    plugin.getDatabaseManager()::getContributionAmountSync);
            if (Double.isNaN(contribution)) {
                return "0.00";
            }
            return String.format("%.2f", contribution);
        } catch (Exception e) {
            plugin.getLogger().warning("获取贡献返点金额失败: " + e.getMessage());
            return "0.00";
        }
    }

    private String resolveEffectiveGiftId(UUID uuid) {
        String giftId = plugin.getCacheManager().getGiftId(uuid, plugin.getDatabaseManager()::getGiftIdSync);
        if (giftId != null) {
            return giftId;
        }
        boolean requireGift = plugin.getConfigManager().getConfig()
            .getBoolean("new_player_reward.require_gift", false);
        if (requireGift) {
            return null;
        }
        return plugin.getConfigManager().getConfig()
            .getString("new_player_reward.default_gift_id", "default");
    }

    // ─── 旧 API 兼容 ───

    public String applyPlaceholders(String text, Player player) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : renderContext(player).strings().entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue())
                    .replace("{" + entry.getKey() + "}", entry.getValue())
                    .replace("%alinvite_" + entry.getKey() + "%", entry.getValue());
        }
        for (Map.Entry<String, String> entry : customReplacements.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return MenuTextPapi.apply(result, player);
    }

    public CompletableFuture<String> applyPlaceholdersAsync(String text, Player player) {
        return renderContextAsync(player).thenApply(context -> {
            if (text == null || text.isEmpty()) {
                return text;
            }
            String result = text;
            for (Map.Entry<String, String> entry : context.strings().entrySet()) {
                result = result.replace("%" + entry.getKey() + "%", entry.getValue())
                        .replace("{" + entry.getKey() + "}", entry.getValue())
                        .replace("%alinvite_" + entry.getKey() + "%", entry.getValue());
            }
            return MenuTextPapi.apply(result, player);
        });
    }

    public CompletableFuture<String> getInviteCodeAsync(UUID uuid) {
        return AsyncPool.supply(() -> getInviteCodeSync(uuid));
    }

    public CompletableFuture<String> getBindStatusAsync(UUID uuid) {
        return AsyncPool.supply(() -> getBindStatusSync(uuid));
    }

    public CompletableFuture<String> getInviterNameAsync(UUID uuid) {
        return AsyncPool.supply(() -> getInviterNameSync(uuid));
    }

    public CompletableFuture<String> getTotalInvitesAsync(UUID uuid) {
        return AsyncPool.supply(() -> getTotalInvitesSync(uuid));
    }

    public CompletableFuture<String> getGiftNameAsync(UUID uuid) {
        return AsyncPool.supply(() -> getGiftNameSync(uuid));
    }

    public CompletableFuture<String> hasActiveGiftAsync(UUID uuid) {
        return AsyncPool.supply(() -> hasActiveGiftSync(uuid));
    }

    public CompletableFuture<String> getGiftStatusAsync(UUID uuid) {
        return AsyncPool.supply(() -> getGiftStatusSync(uuid));
    }

    public CompletableFuture<String> getGiftRemainingDaysAsync(UUID uuid) {
        return AsyncPool.supply(() -> getGiftRemainingDaysSync(uuid));
    }

    public CompletableFuture<String> getNextMilestoneAsync(UUID uuid) {
        return AsyncPool.supply(() -> getNextMilestoneSync(uuid));
    }

    public CompletableFuture<String> getTotalRebateAsync(UUID uuid) {
        return AsyncPool.supply(() -> getTotalRebateSync(uuid));
    }

    public CompletableFuture<String> getContributionAsync(UUID uuid) {
        return AsyncPool.supply(() -> getContributionSync(uuid));
    }

    /** 独立小类避免 gui.render 包反向依赖。 */
    private static final class MenuTextPapi {
        static String apply(String text, Player player) {
            if (text == null || text.isEmpty() || player == null) {
                return text;
            }
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            }
            return text;
        }
    }
}
