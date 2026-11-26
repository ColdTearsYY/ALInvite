package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.database.DatabaseManager;
import com.alinvite.utils.AsyncPool;
import com.alinvite.utils.LuckPermsRebateLookup;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;
import com.alinvite.database.PlayerNameDirectory;
import com.alinvite.utils.LuckPermsPlayerDirectory;
import java.util.concurrent.CompletableFuture;

/**
 * 点券充值返点管理器。
 * 全链 CompletableFuture（IO 线程池），命令发放经统一调度器切实体/全局线程后回调，
 * 不再使用 join/get 反向等待，杜绝 commonPool 阻塞主线程的结构。
 */
public class PointsRebateManager {

    private final ALInvite plugin;
    private final DatabaseManager database;
    private volatile java.util.List<String> knownPlayerNames = java.util.List.of();
    private final java.util.concurrent.atomic.AtomicBoolean refreshingNames = new java.util.concurrent.atomic.AtomicBoolean();
    private final boolean luckPermsAvailable;
    private final java.util.concurrent.atomic.AtomicLong lastLuckPermsRefresh = new java.util.concurrent.atomic.AtomicLong();

    public PointsRebateManager(ALInvite plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabaseManager();
        this.luckPermsAvailable = Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
        refreshKnownPlayerNames();
        plugin.getScheduler().runAsyncTimer(this::refreshKnownPlayerNames, 2400L, 2400L);
    }

    public void recordPlayer(Player player) {
        if (player == null) return;
        String name = player.getName();
        knownPlayerNames = mergeNames(knownPlayerNames, java.util.List.of(name));
        database.upsertPlayerName(player.getUniqueId(), name);
    }

    public java.util.List<String> completePlayerNames(String prefix) {
        java.util.ArrayList<String> sources = new java.util.ArrayList<>(knownPlayerNames);
        for (Player online : Bukkit.getOnlinePlayers()) sources.add(online.getName());
        return com.alinvite.utils.PlayerNameSuggestions.complete(sources, prefix);
    }

    private void refreshKnownPlayerNames() {
        if (!refreshingNames.compareAndSet(false, true)) return;
        database.getKnownPlayerNames().thenCompose(names -> {
            knownPlayerNames = names == null ? java.util.List.of() : names;
            long now = System.currentTimeMillis();
            long previous = lastLuckPermsRefresh.get();
            boolean importLuckPerms = luckPermsAvailable
                && now - previous >= java.util.concurrent.TimeUnit.HOURS.toMillis(1)
                && lastLuckPermsRefresh.compareAndSet(previous, now);
            if (!importLuckPerms) return CompletableFuture.completedFuture(names);
            return LuckPermsPlayerDirectory.loadKnownUsers()
                .thenCompose(players -> database.upsertNetworkPlayers(players)
                    .thenApply(ignored -> {
                        java.util.List<String> lpNames = players.stream()
                            .map(PlayerNameDirectory.PlayerIdentity::currentName).toList();
                        knownPlayerNames = mergeNames(names, lpNames);
                        return knownPlayerNames;
                    }))
                .exceptionally(error -> {
                    plugin.getLogger().warning("从 LuckPerms 刷新全服玩家补全失败: " + error.getMessage());
                    return knownPlayerNames;
                });
        }).whenComplete((names, error) -> {
            if (error != null) plugin.getLogger().warning("刷新全服玩家名称目录失败: " + error.getMessage());
            else if (names != null) knownPlayerNames = names;
            refreshingNames.set(false);
        });
    }

    private static java.util.List<String> mergeNames(java.util.List<String> first, java.util.List<String> second) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        if (first != null) first.forEach(name -> { if (name != null && !name.isBlank()) merged.put(name.toLowerCase(Locale.ROOT), name); });
        if (second != null) second.forEach(name -> { if (name != null && !name.isBlank()) merged.put(name.toLowerCase(Locale.ROOT), name); });
        return java.util.List.copyOf(merged.values());
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
        if (targetPlayer == null || targetPlayer.isBlank() || !Double.isFinite(amount) || amount <= 0) {
            plugin.getLogger().warning("充值参数无效: player=" + targetPlayer + ", amount=" + amount);
            return CompletableFuture.completedFuture(false);
        }
        double minAmount = plugin.getConfigManager().getConfig()
            .getDouble("points_rebate.limits.min_amount", 10.0);
        if (amount < minAmount) {
            plugin.getLogger().warning("充值金额过小: " + amount);
            return CompletableFuture.completedFuture(false);
        }
        String key = transactionKey == null || transactionKey.isBlank()
            ? "evt_" + UUID.randomUUID() : transactionKey;
        return resolveTarget(targetPlayer).thenCompose(target -> {
            if (target == null) {
                plugin.getLogger().warning("玩家不存在于 LuckPerms/共享玩家目录: " + targetPlayer);
                return CompletableFuture.completedFuture(false);
            }
            return checkAntiDuplicate(targetPlayer, key).thenCompose(allowed -> {
                if (!allowed) return CompletableFuture.completedFuture(false);
                return executePointsCommand(target.currentName(), amount).thenCompose(given -> {
                    if (!given || skipRebate) return CompletableFuture.completedFuture(given);
                    return processRebate(target.uuid(), target.currentName(), amount, key);
                });
            });
        }).exceptionally(error -> {
            plugin.getLogger().severe("处理充值返点失败: " + rootMessage(error));
            return false;
        });
    }

    public record ResolvedTarget(UUID uuid, String currentName) { }

    private CompletableFuture<ResolvedTarget> resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return CompletableFuture.completedFuture(new ResolvedTarget(online.getUniqueId(), online.getName()));

        return database.findNetworkPlayer(name).thenCompose(indexed -> {
            if (indexed != null) return CompletableFuture.completedFuture(new ResolvedTarget(indexed.uuid(), indexed.currentName()));
            if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                return CompletableFuture.completedFuture(null);
            }
            return LuckPermsPlayerDirectory.lookupUniqueId(name).thenCompose(uuid -> {
                if (uuid == null) return CompletableFuture.completedFuture(null);
                return LuckPermsPlayerDirectory.lookupUsername(uuid).thenApply(currentName -> {
                    String canonical = currentName == null || currentName.isBlank() ? name : currentName;
                    database.upsertPlayerName(uuid, canonical);
                    return new ResolvedTarget(uuid, canonical);
                });
            });
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private CompletableFuture<Boolean> checkAntiDuplicate(String playerName, String transactionKey) {
        boolean enabled = plugin.getConfigManager().getConfig()
            .getBoolean("points_rebate.anti_duplicate.enabled", true);
        if (!enabled) return CompletableFuture.completedFuture(true);
        return database.checkCrossServerDuplicate(transactionKey).thenApply(duplicate -> {
            if (duplicate) plugin.getLogger().warning("跨服防重复：检测到相同充值流水，玩家=" + playerName + ", key=" + transactionKey);
            return !duplicate;
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

    private CompletableFuture<Boolean> processRebate(UUID targetUuid, String targetPlayer, double amount, String transactionKey) {

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
                        boolean pointsMode = isPointsMode();
                        return resolveRebateRate(inviterUuid).thenCompose(rate -> {
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
                                                Player inviter = Bukkit.getPlayer(inviterUuid);
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
                                                        database.upsertPlayerName(targetUuid, targetPlayer);
                                                        AsyncPool.run(() -> database.insertRebateEventSync(
                                                            inviterUuid, targetUuid, amount, rebateAmount, targetPlayer));
                                                        }
                                                    return database.markPointsRebateRecordProcessed(transactionKey)
                                                        .thenApply(v -> success);
                                                });
                                        });
                                    });
                            });
                        });
                    });
            });
    }

    /** Online display/API: caller must own the player's entity thread. */
    public double getRebateRate(Player player) {
        RebateRatePolicy policy = RebateRatePolicy.from(plugin.getConfigManager().getConfig());
        return player == null ? policy.baseRate() : policy.select(player::hasPermission);
    }

    /** Settlement uses UUID; a player absent from this backend is resolved from LuckPerms storage. */
    public CompletableFuture<Double> resolveRebateRate(UUID inviterUuid) {
        RebateRatePolicy policy = RebateRatePolicy.from(plugin.getConfigManager().getConfig());
        return RebateRateResolver.resolve(inviterUuid, policy, uuid -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) return CompletableFuture.completedFuture(null);
            return plugin.getScheduler().supplyAtPlayer(online,
                () -> online.isOnline() ? policy.select(online::hasPermission) : null);
        }, uuid -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                return CompletableFuture.failedFuture(new IllegalStateException("未启用 LuckPerms，无法查询离线返点权限"));
            }
            return AsyncPool.supply(() -> LuckPermsRebateLookup.rate(uuid, policy))
                .thenCompose(future -> future).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
        }, error -> plugin.getLogger().warning("无法读取邀请人 " + inviterUuid
            + " 的返点权限，本次使用基础比例 " + policy.baseRate() + ": " + error.getMessage()));
    }

    /** 返利模式是否为点券模式（true = 玩家可自行领取；false = 现金模式，管理员核销后线下发放）。 */
    public boolean isPointsMode() {
        String mode = plugin.getConfigManager().getConfig()
            .getString("points_rebate.mode", "points")
            .trim()
            .toLowerCase(java.util.Locale.ROOT);
        return mode.equals("points");
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
        AsyncPool.supply(() -> claimCommand.isEmpty()
                ? database.claimUnclaimedRebateSync(uuid)      // 计入贡献返点余额
                : database.clearUnclaimedRebateSync(uuid))     // 命令模式：仅清零未领取池
            .thenAccept(claimed -> plugin.getScheduler().runAtPlayer(player, () -> {
                if (claimed == null || claimed <= 0) {
                    player.sendMessage(plugin.getConfigManager().getMessage("points_rebate.claim_empty"));
                    plugin.getMenuManager().openMainMenu(player);
                    return;
                }
                String displayAmount = formatAmount(claimed);
                AsyncPool.run(() -> database.addRebateRecordSync(uuid, "claim", claimed, player.getName()));
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


}
