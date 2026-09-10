package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.database.DatabaseManager;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 点券充值返点管理器。
 * 全链 CompletableFuture（IO 线程池），命令发放经统一调度器切实体/全局线程后回调，
 * 不再使用 join/get 反向等待，杜绝 commonPool 阻塞主线程的结构。
 */
public class PointsRebateManager {

    private final ALInvite plugin;
    private final DatabaseManager database;

    /** 缓存的返点权重表（reload 时失效重建）。 */
    private volatile List<String> sortedGroups;

    public PointsRebateManager(ALInvite plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabaseManager();
    }

    /**
     * 处理充值返点：参数验证 → 防重复检查 → 点券发放 → 返点处理。
     *
     * @return 处理结果，true 表示成功
     */
    public CompletableFuture<Boolean> processRecharge(String operator, String targetPlayer, double amount, boolean skipRebate) {
        // 每次充值事件生成唯一流水键；第三方如有自身订单号请使用带 transactionKey 的重载
        return processRecharge(operator, targetPlayer, amount, skipRebate,
            "evt_" + UUID.randomUUID());
    }

    /**
     * 带流水键的充值处理：流水键写入 points_rebate 表（唯一键）占用处理权，
     * 跨服/重复推送同一事件只会发放一次返利。
     */
    public CompletableFuture<Boolean> processRecharge(String operator, String targetPlayer, double amount, boolean skipRebate, String transactionKey) {
        return validateParameters(targetPlayer, amount)
            .thenCompose(valid -> {
                if (!valid) {
                    return CompletableFuture.completedFuture(false);
                }
                return checkAntiDuplicate(targetPlayer, amount);
            })
            .thenCompose(ok -> {
                if (!ok) {
                    plugin.getLogger().warning("防重复检查失败，可能是重复交易: " + targetPlayer);
                    return CompletableFuture.completedFuture(false);
                }
                return executePointsCommand(targetPlayer, amount);
            })
            .thenCompose(given -> {
                if (!given) {
                    return CompletableFuture.completedFuture(false);
                }
                if (skipRebate) {
                    return CompletableFuture.completedFuture(true);
                }
                return processRebate(targetPlayer, amount, transactionKey);
            })
            .exceptionally(throwable -> {
                plugin.getLogger().severe("处理充值返点失败: " + throwable.getMessage());
                return false;
            });
    }

    private CompletableFuture<Boolean> validateParameters(String playerName, double amount) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            plugin.getLogger().warning("玩家不存在: " + playerName);
            return CompletableFuture.completedFuture(false);
        }
        double minAmount = plugin.getConfigManager().getConfig()
            .getDouble("points_rebate.limits.min_amount", 10.0);
        if (amount < minAmount) {
            plugin.getLogger().warning("充值金额过小: " + amount);
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.completedFuture(true);
    }

    /**
     * 防重复检查（跨服）。
     * TODO（行为变更待确认）：写入路径（addPointsRebateRecord/markProcessed）当前无调用方，
     * 检查恒不命中；且 key=uuid+金额，启用后同日同金额两笔会被误判。
     * 激活前需补全写入并改用订单号/时间窗 key。
     */
    private CompletableFuture<Boolean> checkAntiDuplicate(String playerName, double amount) {
        UUID playerUuid = getPlayerUuid(playerName);
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(false);
        }
        boolean antiDuplicateEnabled = plugin.getConfigManager().getConfig()
            .getBoolean("points_rebate.anti_duplicate.enabled", true);
        if (!antiDuplicateEnabled) {
            return CompletableFuture.completedFuture(true);
        }
        String transactionKey = generateTransactionKey(playerUuid, amount);
        return database.checkCrossServerDuplicate(transactionKey)
            .thenApply(isDuplicate -> {
                if (isDuplicate) {
                    plugin.getLogger().warning("跨服防重复：检测到重复充值，玩家=" + playerName + ", 金额=" + amount);
                    return false;
                }
                return true;
            });
    }

    /** 在全局线程执行点券发放命令，回调带回结果（不阻塞调用线程）。 */
    private CompletableFuture<Boolean> executePointsCommand(String playerName, double amount) {
        String command = plugin.getConfigManager().getConfig()
            .getString("points_rebate.points_command", "points give {player} {amount}")
            .replace("{player}", playerName)
            .replace("{amount}", String.valueOf((int) amount));

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        plugin.getScheduler().runGlobal(() -> {
            try {
                boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (success) {
                    plugin.getLogger().info("点券发放成功: " + playerName + " 获得 " + amount + " 点券");
                } else {
                    plugin.getLogger().warning("点券发放失败: " + command);
                }
                future.complete(success);
            } catch (Exception e) {
                plugin.getLogger().severe("执行点券命令失败: " + e.getMessage());
                future.complete(false);
            }
        });
        return future;
    }

    private CompletableFuture<Boolean> processRebate(String targetPlayer, double amount, String transactionKey) {
        UUID targetUuid = getPlayerUuid(targetPlayer);
        if (targetUuid == null) {
            plugin.getLogger().warning("无法获取玩家UUID: " + targetPlayer);
            return CompletableFuture.completedFuture(false);
        }

        return AsyncPool.supply(() -> database.getInviterSync(targetUuid))
            .thenCompose(inviterUuid -> {
                if (inviterUuid == null) {
                    plugin.getLogger().info("玩家 " + targetPlayer + " 没有邀请人，跳过返点");
                    return CompletableFuture.completedFuture(true);
                }
                return AsyncPool.supply(() -> database.getPlayerDataSync(inviterUuid))
                    .thenCompose(inviterData -> {
                        if (inviterData != null && !inviterData.rebateEnabled) {
                            plugin.getLogger().info("邀请人返点功能被禁用，跳过返点: " + inviterUuid);
                            return CompletableFuture.completedFuture(true);
                        }
                        Player inviter = Bukkit.getPlayer(inviterUuid);
                        boolean pointsMode = isPointsMode();
                        double rate = getRebateRate(inviter);
                        double rebateAmount = amount * rate;
                        if (rebateAmount <= 0) {
                            return CompletableFuture.completedFuture(true);
                        }
                        return exceedsDailyLimit(inviterUuid, rebateAmount)
                            .thenCompose(exceeded -> {
                                if (exceeded) {
                                    plugin.getLogger().warning("邀请人 " + inviterUuid + " 今日返点已达上限");
                                    return CompletableFuture.completedFuture(true);
                                }
                                // 流水占用：抢占成功才发放（防跨服/重复推送二次发放）
                                return AsyncPool.supply(() -> database.tryBeginRebateSync(
                                        transactionKey, inviterUuid, amount, inviterUuid, rebateAmount))
                                    .thenCompose(owned -> {
                                        if (!Boolean.TRUE.equals(owned)) {
                                            plugin.getLogger().warning("检测到重复充值流水，跳过返点: key=" + transactionKey);
                                            return CompletableFuture.completedFuture(true);
                                        }
                                        java.util.concurrent.CompletableFuture<Boolean> allowed =
                                                new java.util.concurrent.CompletableFuture<>();
                                        plugin.getScheduler().runGlobal(() -> {
                                            try {
                                                com.alinvite.api.event.RebateGrantEvent event =
                                                    new com.alinvite.api.event.RebateGrantEvent(
                                                        inviter, targetPlayer, amount, rebateAmount, pointsMode);
                                                Bukkit.getPluginManager().callEvent(event);
                                                allowed.complete(!event.isCancelled());
                                            } catch (Exception e) {
                                                allowed.complete(true);
                                            }
                                        });
                                        return allowed.thenCompose(permitted -> {
                                            if (!Boolean.TRUE.equals(permitted)) {
                                                plugin.getLogger().info("返点发放被其它插件取消: " + inviterUuid);
                                                return CompletableFuture.completedFuture(true);
                                            }
                                            // 统一进入未领取池：点券模式玩家可自行领取，现金模式由管理员核销
                                            return parkRebate(inviterUuid, rebateAmount, targetPlayer, amount, pointsMode)
                                                .thenCompose(success -> {
                                                    // 事件流水（可追溯，也是窗口期判重依据）
                                                    if (success) {
                                                        UUID sourceUuid = getPlayerUuid(targetPlayer);
                                                        if (sourceUuid != null) {
                                                            AsyncPool.run(() -> database.insertRebateEventSync(
                                                                inviterUuid, sourceUuid, amount, rebateAmount, targetPlayer));
                                                        }
                                                    }
                                                    return database.markPointsRebateRecordProcessed(transactionKey)
                                                        .thenApply(v -> success);
                                                });
                                        });
                                    });
                            });
                    });
            });
    }

    /**
     * 返点比例：按配置权重从高到低检查权限组，恒为正值。
     * 到账方式（点券/现金）由全局 mode 决定，与比例无关。
     */
    public double getRebateRate(Player player) {
        if (player == null) {
            return getDefaultRate();
        }
        String permissionPrefix = plugin.getConfigManager().getConfig()
            .getString("points_rebate.permission_prefix", "alinvite.rebate");

        for (String group : getSortedGroups()) {
            String permission = permissionPrefix + "." + group;
            if (player.hasPermission(permission)) {
                double rate = plugin.getConfigManager().getConfig()
                    .getDouble("points_rebate.rebate_rates." + group + ".rate", 0.10);
                if (plugin.getConfigManager().getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("玩家 " + player.getName() + " 命中返点权限组 " + group
                        + "，比例: " + (rate * 100) + "%");
                }
                return rate;
            }
        }

        double baseRate = plugin.getConfigManager().getConfig()
            .getDouble("points_rebate.rebate_rates.base.rate", 0.05);
        if (plugin.getConfigManager().getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("玩家 " + player.getName() + " 使用基础返点比例: " + (baseRate * 100) + "%");
        }
        return baseRate;
    }

    /** 返利模式是否为点券模式（true = 玩家可自行领取；false = 现金模式，管理员核销后线下发放）。 */
    public boolean isPointsMode() {
        String mode = plugin.getConfigManager().getConfig()
            .getString("points_rebate.mode", "points")
            .trim()
            .toLowerCase(java.util.Locale.ROOT);
        return mode.equals("points");
    }

    private List<String> getSortedGroups() {
        List<String> cached = sortedGroups;
        if (cached != null) {
            return cached;
        }
        String[] groups = {"contribution", "admin", "mvip", "svip", "vip", "default"};
        Map<String, Integer> weights = new HashMap<>();
        for (String group : groups) {
            weights.put(group, plugin.getConfigManager().getConfig()
                .getInt("points_rebate.rebate_rates." + group + ".weight", 1));
        }
        List<String> sorted = new java.util.ArrayList<>(Arrays.asList(groups));
        sorted.sort((a, b) -> Integer.compare(weights.get(b), weights.get(a)));
        sortedGroups = sorted;
        return sorted;
    }

    private CompletableFuture<Boolean> exceedsDailyLimit(UUID playerUuid, double newRebate) {
        double dailyLimit = plugin.getConfigManager().getConfig()
            .getDouble("points_rebate.limits.max_rebate_per_day", 1000.0);
        return database.getTodayRebateTotal(playerUuid)
            .thenApply(todayRebate -> (todayRebate + newRebate) > dailyLimit);
    }

    /**
     * 返利统一进入"未领取池"并写入返利记录：
     *  - 点券模式：玩家可在主菜单 S 键手动领取，计入贡献返点余额；
     *  - 现金模式：玩家不可自行领取，由管理员核销后线下发放。
     */
    /**
     * 返利统一进入"未领取池"并写入返利记录（邀请人不在线也可入池，上线后领取）。
     * 点券模式下本方法不再被调用（已无即时发放路径），保留以兼容扩展。
     */
    private CompletableFuture<Boolean> parkRebate(UUID playerUuid, double amount, String targetPlayer, double originalAmount, boolean pointsMode) {
        return AsyncPool.supply(() -> {
            database.addUnclaimedRebateSync(playerUuid, amount);
            database.addRebateRecordSync(playerUuid, "rebate", amount, targetPlayer);
            database.updateTotalRebatePointsSync(playerUuid, amount);
            return true;
        }).thenApply(success -> {
            if (!success) {
                plugin.getLogger().warning("返利入池失败: " + playerUuid);
                return false;
            }
            // 在线则实时通知；离线玩家上线后可在主菜单领取
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) {
                String message = plugin.getConfigManager().getMessage("points_rebate.rebate_pending_notify")
                    .replace("{player}", targetPlayer)
                    .replace("{amount}", String.valueOf((int) originalAmount))
                    .replace("{rebate_amount}", formatAmount(amount));
                plugin.getScheduler().runAtPlayer(player, () -> player.sendMessage(message));
            }
            return true;
        });
    }

    /**
     * 玩家手动领取未领取返点（主菜单 S 按钮左键），行为由模式决定：
     *  - 点券模式（contribution_mode: true）：清零未领取池并执行
     *    config 的 points_rebate.claim_command（适配任意点券插件）；
     *    命令未配置时计入贡献返点余额。
     *  - 现金模式（contribution_mode: false）：不可自行领取，
     *    提示联系管理员；管理员核销（unclaimed clear）后线下发放。
     */
    public void claimRebate(Player player) {
        UUID uuid = player.getUniqueId();
        boolean pointsMode = isPointsMode();
        if (!pointsMode) {
            // 现金模式：不可自行领取，须管理员核销后线下发放
            plugin.getScheduler().runAtPlayer(player, () ->
                player.sendMessage(plugin.getConfigManager().getMessage("points_rebate.claim_blocked_cash")));
            return;
        }
        final String claimCommand = plugin.getConfigManager().getConfig()
            .getString("points_rebate.claim_command", "").trim();
        AsyncPool.supply(() -> {
                Double claimed = claimCommand.isEmpty()
                    ? database.claimUnclaimedRebateSync(uuid)      // 计入贡献返点余额
                    : database.clearUnclaimedRebateSync(uuid);     // 命令模式：仅清零未领取池
                // 领取动作落一条 claim 记录，返利记录菜单的领取视图可见
                if (claimed != null && claimed > 0) {
                    database.addRebateRecordSync(uuid, "claim", claimed, null);
                }
                return claimed;
            })
            .thenAccept(claimed -> plugin.getScheduler().runAtPlayer(player, () -> {
                if (claimed == null || claimed <= 0) {
                    player.sendMessage(plugin.getConfigManager().getMessage("points_rebate.claim_empty"));
                    plugin.getMenuManager().openMainMenu(player);
                    return;
                }
                String displayAmount = formatAmount(claimed);
                if (!claimCommand.isEmpty()) {
                    // 点券模式 + 已配置发放命令：全局线程执行，适配任意点券插件
                    String cmd = claimCommand
                        .replace("{player}", player.getName())
                        .replace("{amount}", displayAmount);
                    plugin.getScheduler().runGlobal(() -> {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                        } catch (Exception e) {
                            plugin.getLogger().severe("执行返利领取命令失败: " + cmd + " - " + e.getMessage());
                        }
                    });
                    player.sendMessage(plugin.getConfigManager().getMessage("points_rebate.claim_success_command")
                        .replace("{amount}", displayAmount));
                } else {
                    player.sendMessage(plugin.getConfigManager().getMessage("points_rebate.claim_success")
                        .replace("{amount}", displayAmount));
                    plugin.getCacheManager().invalidateContribution(uuid);
                }
                plugin.getSync().sendInvalidate(uuid, "contribution", "rebate", "unclaimed");
                plugin.getMenuManager().openMainMenu(player);
            }));
    }

    /**
     * 玩家当前返点比例展示文本（含贡献模式标记），供 GUI / 占位符使用。
     */
    public String getRebateRateDisplay(Player player) {
        double rate = getRebateRate(player);
        boolean pointsMode = isPointsMode();
        String percent = rate * 100 == Math.floor(rate * 100) && !Double.isInfinite(rate)
            ? String.valueOf((long) (rate * 100))
            : String.format(Locale.ROOT, "%.1f", rate * 100);
        String tag = plugin.getConfigManager().getMessageRaw(
            pointsMode ? "points_rebate.mode_tag_points" : "points_rebate.mode_tag_cash");
        return percent + "%" + tag;
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) && !Double.isInfinite(amount)
            ? String.valueOf((long) amount)
            : String.format(Locale.ROOT, "%.2f", amount);
    }

    /**
     * 跨服交易标识。
     * TODO（行为变更待确认）：当前为 uuid+金额，激活防重复前应改为订单号或加时间窗，
     * 否则同日同金额两笔充值会被误判为重复。
     */
    private String generateTransactionKey(UUID playerUuid, double amount) {
        return String.format(Locale.ROOT, "rebate_%s_%.2f", playerUuid, amount);
    }

    private UUID getPlayerUuid(String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        return player != null ? player.getUniqueId() : null;
    }

    private double getDefaultRate() {
        return plugin.getConfigManager().getConfig()
            .getDouble("points_rebate.rebate_rates.default.rate", 0.10);
    }
}
