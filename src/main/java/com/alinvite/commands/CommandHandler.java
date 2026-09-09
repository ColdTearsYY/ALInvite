package com.alinvite.commands;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.utils.PapiDetector;
import com.alinvite.utils.PlaceholderResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private final ALInvite plugin;

    public CommandHandler(ALInvite plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("errors.player_only"));
                return true;
            }
            plugin.getMenuManager().openMainMenu(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "code" -> handleCode(sender);
            case "bind" -> handleBind(sender, args);
            case "stats" -> handleStats(sender);
            case "contrib" -> handleContribution(sender);
            case "buygift" -> handleBuyGift(sender);
            case "help" -> handleHelp(sender);
            case "admin" -> handleAdmin(sender, args);
            case "givedj" -> handleGiveDj(sender, args);

            default -> sender.sendMessage(plugin.getConfigManager().getMessage("errors.unknown"));
        }

        return true;
    }

    private void handleCode(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.player_only"));
            return;
        }

        if (!player.hasPermission("alinvite.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }

        // 检查玩家是否有老玩家权限
        String veteranPermission = plugin.getConfigManager().getConfig()
            .getString("invite_code.veteran_permission", "alinvite.veteran");
        
        if (!player.hasPermission(veteranPermission)) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_invite_code"));
            return;
        }

        plugin.getInviteManager().getInviteCode(player.getUniqueId()).thenCompose(code -> {
            if (code == null) {
                return plugin.getInviteManager().generateInviteCode(player.getUniqueId());
            }
            return CompletableFuture.completedFuture(code);
        }).thenAccept(code -> {
            String message = plugin.getConfigManager().getMessage("commands.code", player).replace("{invite_code}", code);
            player.sendMessage(message);
        });
    }

    private void handleBind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.player_only"));
            return;
        }

        if (!player.hasPermission("alinvite.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }

        if (args.length < 2) {
            plugin.getMenuManager().getInputService().startCodeInput(player);
            return;
        }

        String code = args[1].trim().toUpperCase();

        if (code.isEmpty()) {
            player.sendMessage(ConfigManager.colorize("&c邀请码不能为空！"));
            return;
        }

        plugin.getInviteManager().bindInviteCode(player, code).thenAccept(result -> {
            if (result.success) {
                player.sendMessage(plugin.getConfigManager().getMessage("dialog.success"));
            } else {
                String reason = switch (result.type) {
                    case NO_PERMISSION -> plugin.getConfigManager().getMessage("errors.no_permission");
                    case CODE_NOT_FOUND -> plugin.getConfigManager().getMessage("dialog.fail");
                    case ALREADY_USED -> plugin.getConfigManager().getMessage("errors.already_used");
                    case IP_LIMIT -> plugin.getConfigManager().getMessage("dialog.ip_limit");
                    case SELF_INVITE -> plugin.getConfigManager().getMessage("dialog.self_invite");
                    case VETERAN_CANNOT_BIND -> plugin.getConfigManager().getMessage("errors.veteran_cannot_bind");
                    case INVITER_LIMIT_REACHED -> plugin.getConfigManager().getMessage("errors.inviter_limit_reached");
                    default -> plugin.getConfigManager().getMessage("dialog.fail");
                };
                player.sendMessage(reason);
            }
        });
    }

    private void handleHelp(CommandSender sender) {
        String message = plugin.getConfigManager().getMessage("commands.help");
        sender.sendMessage(message);
    }

    private void handleStats(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.player_only"));
            return;
        }

        if (!player.hasPermission("alinvite.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }

        plugin.getInviteManager().getTotalInvites(player.getUniqueId())
            .thenCompose(total -> plugin.getDatabaseManager().getClaimedMilestones(player.getUniqueId())
                .thenCompose(claimedJson -> plugin.getDatabaseManager().getGiftId(player.getUniqueId())
                    .thenApply(giftId -> {
                        String giftName = "无";
                        if (giftId != null) {
                            var gift = plugin.getGiftManager().getGift(giftId);
                            if (gift != null) {
                                giftName = ConfigManager.colorize(gift.name);
                            }
                        }
                        return new Object[]{total, claimedJson, giftName};
                    })))
            .thenAccept(payload -> {
                Object[] parts = (Object[]) payload;
                String message = plugin.getConfigManager().getMessage("commands.stats", player)
                    .replace("{total}", String.valueOf(parts[0]))
                    .replace("{claimed_milestones}", formatClaimedMilestones((String) parts[1]))
                    .replace("{gift_name}", (String) parts[2]);
                plugin.getScheduler().runAtPlayer(player, () -> player.sendMessage(message));
            });
    }

    /** 已领取里程碑列表渲染为友好文本（修复显示原始 JSON 的问题）。 */
    private String formatClaimedMilestones(String claimedJson) {
        if (claimedJson == null || claimedJson.isBlank() || claimedJson.equals("[]")) {
            return "0";
        }
        java.util.List<String> ids = new ArrayList<>();
        for (String part : claimedJson.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return ids.isEmpty() ? "0" : String.join(", ", ids);
    }

    private void handleBuyGift(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.player_only"));
            return;
        }

        if (!player.hasPermission("alinvite.buygift")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }

        plugin.getMenuManager().openShopMenu(player);
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("alinvite.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getConfigManager().getMessageRaw("commands.admin.help"));
            return;
        }

        String adminSub = args[1].toLowerCase();

        switch (adminSub) {
            case "help" -> {
                sender.sendMessage(plugin.getConfigManager().getMessageRaw("commands.admin.help"));
            }
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(plugin.getConfigManager().getMessage("commands.reload"));
            }
            case "givecode" -> {
                if (args.length < 3) {
                    sender.sendMessage("用法: /alinvite admin givecode <玩家>");
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage("玩家不存在或不在线");
                    return;
                }
                plugin.getInviteManager().generateInviteCode(target.getUniqueId()).thenAccept(code -> {
                    String message = plugin.getConfigManager().getMessage("commands.admin.givecode_success")
                        .replace("{player}", target.getName())
                        .replace("{invite_code}", code);
                    sender.sendMessage(message);
                });
            }
            case "clearcode" -> {
                if (args.length < 3) {
                    sender.sendMessage("用法: /alinvite admin clearcode <玩家>");
                    return;
                }
                UUID targetUuid = getPlayerUuid(args[2]);

                plugin.getDatabaseManager().clearInviteCode(targetUuid).thenRun(() -> {
                    plugin.getCacheManager().invalidateInviteCode(targetUuid);

                    String msg = plugin.getConfigManager().getMessage("commands.admin.clearcode_success")
                        .replace("{player}", args[2]);
                    sender.sendMessage(msg);
                });
            }
            case "contrib" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.getConfigManager().getMessageRaw("commands.admin.help"));
                    return;
                }
                
                String action = args[2].toLowerCase();
                
                switch (action) {
                    case "add" -> {
                        if (args.length < 5) {
                            sender.sendMessage(ConfigManager.colorize("&c用法: /alinvite admin contrib add <玩家> <金额>"));
                            return;
                        }
                        
                        UUID targetUuid = getPlayerUuid(args[3]);
                        Player target = Bukkit.getPlayer(targetUuid);
                        String playerName = (target != null) ? target.getName() : args[3];
                        
                        double addAmount;
                        try {
                            addAmount = Double.parseDouble(args[4]);
                            if (addAmount <= 0) {
                                sender.sendMessage(ConfigManager.colorize("&c增加金额必须大于0"));
                                return;
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ConfigManager.colorize("&c金额必须是数字"));
                            return;
                        }
                        
                        plugin.getDatabaseManager().addContributionAmount(targetUuid, addAmount).thenAccept(success -> {
                            if (success) {
                                String message = ConfigManager.colorize("&a成功为玩家 " + playerName + " 增加 " + addAmount + " 贡献返点");
                                sender.sendMessage(message);
                                
                                // 发送消息给玩家（如果在线）
                                if (target != null) {
                                    String playerMsg = ConfigManager.colorize("&a您的贡献返点增加了 " + addAmount + " 点券");
                                    target.sendMessage(playerMsg);
                                }
                            } else {
                                sender.sendMessage(ConfigManager.colorize("&c操作失败"));
                            }
                        });
                    }
                    case "set" -> {
                        if (args.length < 5) {
                            sender.sendMessage(ConfigManager.colorize("&c用法: /alinvite admin contrib set <玩家> <金额>"));
                            return;
                        }
                        
                        UUID targetUuid = getPlayerUuid(args[3]);
                        Player target = Bukkit.getPlayer(targetUuid);
                        String playerName = (target != null) ? target.getName() : args[3];
                        
                        double setAmount;
                        try {
                            setAmount = Double.parseDouble(args[4]);
                            if (setAmount < 0) {
                                sender.sendMessage(ConfigManager.colorize("&c设置金额不能为负数"));
                                return;
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ConfigManager.colorize("&c金额必须是数字"));
                            return;
                        }
                        
                        plugin.getDatabaseManager().setContributionAmount(targetUuid, setAmount).thenAccept(success -> {
                            if (success) {
                                String message = ConfigManager.colorize("&a成功将玩家 " + playerName + " 的贡献返点设置为 " + setAmount);
                                sender.sendMessage(message);
                                
                                // 发送消息给玩家（如果在线）
                                if (target != null) {
                                    String playerMsg = ConfigManager.colorize("&a您的贡献返点已被设置为 " + setAmount + " 点券");
                                    target.sendMessage(playerMsg);
                                }
                            } else {
                                sender.sendMessage(ConfigManager.colorize("&c操作失败"));
                            }
                        });
                    }
                    case "deduct" -> {
                        if (args.length < 5) {
                            sender.sendMessage(ConfigManager.colorize("&c用法: /alinvite admin contrib deduct <玩家> <金额>"));
                            return;
                        }
                        
                        UUID targetUuid = getPlayerUuid(args[3]);
                        Player target = Bukkit.getPlayer(targetUuid);
                        String playerName = (target != null) ? target.getName() : args[3];
                        
                        double deductAmount;
                        try {
                            deductAmount = Double.parseDouble(args[4]);
                            if (deductAmount <= 0) {
                                sender.sendMessage(ConfigManager.colorize("&c扣除金额必须大于0"));
                                return;
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ConfigManager.colorize("&c金额必须是数字"));
                            return;
                        }
                        
                        plugin.getDatabaseManager().deductContributionAmount(targetUuid, deductAmount).thenAccept(success -> {
                            if (success) {
                                String message = plugin.getConfigManager().getMessage("commands.admin.contribution_deducted")
                                    .replace("{player}", playerName)
                                    .replace("{amount}", String.valueOf((int)deductAmount));
                                sender.sendMessage(message);
                                
                                // 发送消息给玩家（如果在线）
                                if (target != null) {
                                    String playerMsg = plugin.getConfigManager().getMessage("commands.admin.contribution_player_deducted")
                                        .replace("{amount}", String.valueOf((int)deductAmount));
                                    target.sendMessage(playerMsg);
                                }
                            } else {
                                sender.sendMessage(ConfigManager.colorize("&c扣除失败: 玩家贡献返点余额不足"));
                            }
                        });
                    }
                    case "clear" -> {
                        if (args.length < 4) {
                            sender.sendMessage(ConfigManager.colorize("&c用法: /alinvite admin contrib clear <玩家>"));
                            return;
                        }
                        
                        UUID targetUuid = getPlayerUuid(args[3]);
                        Player target = Bukkit.getPlayer(targetUuid);
                        String playerName = (target != null) ? target.getName() : args[3];
                        
                        plugin.getDatabaseManager().clearContributionAmount(targetUuid).thenAccept(success -> {
                            if (success) {
                                String message = ConfigManager.colorize("&a成功清空玩家 " + playerName + " 的贡献返点");
                                sender.sendMessage(message);
                                
                                // 发送消息给玩家（如果在线）
                                if (target != null) {
                                    String playerMsg = ConfigManager.colorize("&a您的贡献返点已被清空");
                                    target.sendMessage(playerMsg);
                                }
                            } else {
                                sender.sendMessage(ConfigManager.colorize("&c操作失败"));
                            }
                        });
                    }
                    case "h" -> {
                        // 兑换贡献返点为点券
                        if (args.length < 5) {
                            sender.sendMessage(ConfigManager.colorize("&c用法: /alinvite admin contrib h <玩家> <金额>"));
                            return;
                        }
                        
                        Player target = Bukkit.getPlayer(args[3]);
                        if (target == null) {
                            sender.sendMessage(ConfigManager.colorize("&c玩家不存在或不在线"));
                            return;
                        }
                        
                        double exchangeAmount;
                        try {
                            exchangeAmount = Double.parseDouble(args[4]);
                            if (exchangeAmount <= 0) {
                                sender.sendMessage(ConfigManager.colorize("&c兑换金额必须大于0"));
                                return;
                            }
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ConfigManager.colorize("&c兑换金额必须是数字"));
                            return;
                        }
                        
                        plugin.getDatabaseManager().deductContributionAmount(target.getUniqueId(), exchangeAmount).thenAccept(success -> {
                            if (success) {
                                String pointsCommand = plugin.getConfigManager().getConfig()
                                    .getString("points_rebate.points_command", "points give {player} {amount}")
                                    .replace("{player}", target.getName())
                                    .replace("{amount}", String.valueOf((int)exchangeAmount));
                                
                                final String finalPointsCommand = pointsCommand;
                                plugin.getLogger().info("执行兑换命令: " + finalPointsCommand);
                                
                                plugin.getScheduler().runGlobal(() -> {
                                    boolean pointsSuccess = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalPointsCommand);
                                    
                                    if (pointsSuccess) {
                                        plugin.getLogger().info("点券发放成功: " + target.getName() + " 获得 " + exchangeAmount + " 点券");
                                        String message = plugin.getConfigManager().getMessage("commands.admin.contribution_exchanged")
                                            .replace("{player}", target.getName())
                                            .replace("{amount}", String.valueOf((int)exchangeAmount));
                                        sender.sendMessage(message);
                                        
                                        String playerMsg = plugin.getConfigManager().getMessage("commands.admin.contribution_player_exchanged")
                                            .replace("{amount}", String.valueOf((int)exchangeAmount));
                                        target.sendMessage(playerMsg);
                                    } else {
                                        plugin.getLogger().warning("点券发放失败: " + finalPointsCommand);
                                        String message = plugin.getConfigManager().getMessage("commands.admin.contribution_exchange_failed")
                                            .replace("{player}", target.getName())
                                            .replace("{amount}", String.valueOf((int)exchangeAmount));
                                        sender.sendMessage(message);
                                        sender.sendMessage(ConfigManager.colorize("&c请手动发放点券: " + finalPointsCommand));
                                        
                                        String playerMsg = ConfigManager.colorize("&c贡献返点兑换失败，请联系管理员");
                                        target.sendMessage(playerMsg);
                                    }
                                });
                            } else {
                                sender.sendMessage(ConfigManager.colorize("&c兑换失败: 玩家贡献返点余额不足"));
                            }
                        });
                    }
                    default -> {
                        // 检查是否是有效的子命令
                        if (action.equals("add") || action.equals("set") || action.equals("deduct") || action.equals("clear") || action.equals("h")) {
                            // 提示输入玩家名字
                            sender.sendMessage(ConfigManager.colorize("&c请输入玩家名字: /alinvite admin contrib " + action + " <玩家>"));
                            return;
                        }
                        
                        // 查询贡献返点
                        UUID targetUuid = getPlayerUuid(args[2]);
                        Player target = Bukkit.getPlayer(targetUuid);
                        String playerName = (target != null) ? target.getName() : args[2];
                        
                        plugin.getDatabaseManager().getContributionAmount(targetUuid).thenAccept(amount -> {
                            String message = ConfigManager.colorize("&a玩家 " + playerName + " 的贡献返点余额: &e" + amount + " &a点券");
                            sender.sendMessage(message);
                        });
                    }
                }
            }
            case "addinvite" -> {
                if (args.length < 4) {
                    sender.sendMessage("用法: /alinvite admin addinvite <玩家> <数量>");
                    return;
                }
                UUID targetUuid = getPlayerUuid(args[2]);
                Player target = Bukkit.getPlayer(targetUuid);
                String playerName = (target != null) ? target.getName() : args[2];
                int amount;
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("数量必须是数字");
                    return;
                }

                plugin.getDatabaseManager().getPlayerData(targetUuid).thenAccept(data -> {
                    if (data != null) {
                        int newTotal = data.totalInvites + amount;
                        plugin.getDatabaseManager().updateInviteCount(targetUuid, newTotal).thenRun(() -> {
                            plugin.getCacheManager().invalidateStats(targetUuid);
                            plugin.getMilestoneManager().checkMilestones(targetUuid, newTotal);

                            String message = plugin.getConfigManager().getMessage("commands.admin.addinvite_success")
                                .replace("{player}", playerName)
                                .replace("{amount}", String.valueOf(amount));
                            sender.sendMessage(message);
                        });
                    } else {
                        sender.sendMessage("玩家不存在");
                    }
                });
            }
            case "reset" -> {
                if (args.length < 3) {
                    sender.sendMessage("用法: /alinvite admin reset <玩家>");
                    return;
                }
                UUID targetUuid = getPlayerUuid(args[2]);

                plugin.getDatabaseManager().resetPlayerData(targetUuid).thenRun(() -> {
                    plugin.getCacheManager().invalidateStats(targetUuid);
                    plugin.getCacheManager().invalidateInviteCode(targetUuid);
                    plugin.getCacheManager().invalidateGiftId(targetUuid);

                    String message = plugin.getConfigManager().getMessage("commands.admin.reset_success")
                        .replace("{player}", args[2]);
                    sender.sendMessage(message);
                });
            }
            case "announce" -> {
                if (args.length < 4) {
                    sender.sendMessage("用法: /alinvite admin announce <玩家> <里程碑值>");
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage("玩家不存在或不在线");
                    return;
                }
                int milestoneValue;
                try {
                    milestoneValue = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("里程碑值必须是数字");
                    return;
                }

                var milestone = plugin.getMilestoneManager().getMilestone(milestoneValue);
                if (milestone != null) {
                    String message = plugin.getConfigManager().getConfig()
                        .getString("announcements.messages." + milestoneValue,
                            plugin.getConfigManager().getConfig()
                                .getString("announcements.messages.default", ""));
                    message = message.replace("{player}", target.getName())
                        .replace("{total}", String.valueOf(milestoneValue))
                        .replace("{milestone_name}", milestone.name);
                    plugin.getServer().broadcastMessage(ConfigManager.colorize(message));

                    sender.sendMessage(plugin.getConfigManager().getMessage("commands.admin.announce_success"));
                }
            }
            case "unclaimed" -> {
                // /alinvite admin unclaimed <玩家>              查看未领取余额
                // /alinvite admin unclaimed clear <玩家>        核销清零（线下已发放现金后执行）
                if (args.length < 3) {
                    sender.sendMessage("用法: /alinvite admin unclaimed <玩家> | unclaimed clear <玩家>");
                    return;
                }
                if (args[2].equalsIgnoreCase("clear")) {
                    if (args.length < 4) {
                        sender.sendMessage("用法: /alinvite admin unclaimed clear <玩家>");
                        return;
                    }
                    UUID targetUuid = getPlayerUuid(args[3]);
                    plugin.getDatabaseManager().clearUnclaimedRebate(targetUuid).thenAccept(cleared -> {
                        String msg = cleared != null && cleared > 0
                            ? "已核销玩家 " + args[3] + " 的未领取返点: " + String.format("%.2f", cleared) + " 点券（请确认已线下发放）"
                            : "玩家 " + args[3] + " 没有未领取的返点";
                        sender.sendMessage(msg);
                    });
                    return;
                }
                UUID targetUuid = getPlayerUuid(args[2]);
                plugin.getDatabaseManager().getUnclaimedRebate(targetUuid).thenAccept(amount -> {
                    String msg = amount != null && amount > 0
                        ? "玩家 " + args[2] + " 的未领取返点: " + String.format("%.2f", amount) + " 点券"
                        : "玩家 " + args[2] + " 没有未领取的返点";
                    sender.sendMessage(msg);
                });
            }
            case "rebate" -> {
                if (args.length < 3) {
                    sender.sendMessage("用法: /alinvite admin rebate <玩家>");
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage("玩家不存在或不在线");
                    return;
                }
                plugin.getMenuManager().openRebateHistoryMenu(target);
                sender.sendMessage("已为玩家 " + target.getName() + " 打开返利记录菜单");
            }
            case "checkgroup" -> {
                if (args.length < 3) {
                    sender.sendMessage("用法: /alinvite admin checkgroup <玩家>");
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage("玩家不存在或不在线");
                    return;
                }
                plugin.getScheduler().runGlobal(() -> {
                    plugin.getPermissionGroupRewardListener().manualCheck(target);
                    sender.sendMessage("已为玩家 " + target.getName() + " 检查权限组奖励");
                });
            }
            case "papi" -> handlePapiAdmin(sender, args);
            default -> {
                sender.sendMessage(plugin.getConfigManager().getMessageRaw("commands.admin.help"));
            }
        }
    }

    private UUID getPlayerUuid(String input) {
        // 先尝试解析为 UUID
        try {
            UUID uuid = UUID.fromString(input);
            return uuid;
        } catch (IllegalArgumentException ignored) {
            // 不是 UUID，尝试按玩家名查找
            var offline = Bukkit.getOfflinePlayer(input);
            // 如果玩家玩过，返回 UUID
            if (offline.hasPlayedBefore()) {
                return offline.getUniqueId();
            }
            // 即使没玩过，也尝试通过数据库查找是否有相关数据
            // 先不返回 null，让数据库操作去处理
            return offline.getUniqueId();
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("code", "stats", "contrib", "buygift", "help", "admin", "givedj", "bind"));
            return filterByInput(completions, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            completions.addAll(Arrays.asList("reload", "givecode", "clearcode", "addinvite", "reset", "announce", "checkgroup", "contrib", "papi", "rebate", "unclaimed"));
            return filterByInput(completions, args[1]);
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("givecode")) {
            return filterByInput(
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                args[2]
            );
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("addinvite")) {
            return filterByInput(
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                args[2]
            );
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("unclaimed")) {
            return filterByInput(Arrays.asList("clear"), args[2]);
        }

        if (args.length == 3 && (args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("clearcode") || args[1].equalsIgnoreCase("checkgroup") || args[1].equalsIgnoreCase("rebate"))) {
            return filterByInput(
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                args[2]
            );
        }
        
        // contrib 指令特殊处理：先补全子指令
        if (args.length == 3 && args[1].equalsIgnoreCase("contrib")) {
            return filterByInput(Arrays.asList("add", "set", "deduct", "clear", "h"), args[2]);
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("addinvite")) {
            return filterByInput(Arrays.asList("1", "5", "10", "100"), args[3]);
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("announce")) {
            return filterByInput(
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                args[2]
            );
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("announce")) {
            return filterByInput(
                plugin.getMilestoneManager().getMilestones().keySet().stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList()),
                args[3]
            );
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("contrib")) {
            // 补全玩家名字
            return filterByInput(
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                args[3]
            );
        }

        if (args.length == 5 && args[1].equalsIgnoreCase("contrib") && (args[3].equalsIgnoreCase("add") || args[3].equalsIgnoreCase("set") || args[3].equalsIgnoreCase("deduct") || args[3].equalsIgnoreCase("h"))) {
            return filterByInput(Arrays.asList("100", "500", "1000", "5000"), args[4]);
        }

        // givedj 命令补全
        if (args.length == 2 && args[0].equalsIgnoreCase("givedj")) {
            return filterByInput(
                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                args[1]
            );
        }

        // papi 子命令补全
        if (args.length == 3 && args[1].equalsIgnoreCase("papi")) {
            return filterByInput(Arrays.asList("list", "scan", "test"), args[2]);
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("papi")) {
            String papiAction = args[2].toLowerCase();
            if ("scan".equals(papiAction)) {
                return filterByInput(Arrays.asList("main_menu", "veteran_menu", "shop_menu"), args[3]);
            }
            if ("test".equals(papiAction)) {
                return filterByInput(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                    args[3]
                );
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("givedj")) {
            return filterByInput(Arrays.asList("100", "500", "1000", "5000"), args[2]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("givedj")) {
            return filterByInput(Arrays.asList("-norebate"), args[3]);
        }

        return completions;
    }

    private List<String> filterByInput(List<String> list, String input) {
        if (input.isEmpty()) return list;
        String lowerInput = input.toLowerCase();
        return list.stream()
            .filter(s -> s.toLowerCase().startsWith(lowerInput))
            .collect(Collectors.toList());
    }

    private void handleGiveDj(CommandSender sender, String[] args) {
        if (!sender.hasPermission("alinvite.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("用法: /alinvite givedj <玩家> <点券数量> [-norebate]");
            sender.sendMessage("参数说明:");
            sender.sendMessage("  - 玩家: 接收点券的玩家名称");
            sender.sendMessage("  - 点券数量: 充值的点券数量");
            sender.sendMessage("  - -norebate: 可选参数，跳过返点处理");
            return;
        }

        String targetPlayer = args[1];
        double amount;
        
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("点券数量必须是数字");
            return;
        }

        boolean skipRebate = args.length >= 4 && "-norebate".equalsIgnoreCase(args[3]);

        plugin.getPointsRebateManager().processRecharge(sender.getName(), targetPlayer, amount, skipRebate)
            .thenAccept(success -> {
                if (success) {
                    String message = skipRebate 
                        ? "已成功为玩家 " + targetPlayer + " 充值 " + amount + " 点券（跳过返点）"
                        : "已成功为玩家 " + targetPlayer + " 充值 " + amount + " 点券，返点已处理";
                    sender.sendMessage(message);
                } else {
                    sender.sendMessage("充值失败，请检查玩家名称和金额");
                }
            });
    }
    
    /**
     * 处理 /alinvite admin papi 命令 - PlaceholderAPI 检测与管理
     */
    private void handlePapiAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("alinvite.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }

        if (!PapiDetector.isPapiAvailable()) {
            sender.sendMessage(ConfigManager.colorize("&cPlaceholderAPI 未安装！此功能需要 PlaceholderAPI 插件。"));
            return;
        }

        if (args.length < 3) {
            sendPapiHelp(sender);
            return;
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "list" -> {
                sender.sendMessage(ConfigManager.colorize("&6━━━━━━ &e已注册的 PlaceholderAPI 扩展 &6━━━━━━"));
                java.util.Map<String, String> expansions = PapiDetector.getExpansionInfo();
                if (expansions.isEmpty()) {
                    sender.sendMessage(ConfigManager.colorize("&7  无已注册的扩展"));
                } else {
                    int count = 0;
                    for (java.util.Map.Entry<String, String> entry : expansions.entrySet()) {
                        String marker = entry.getKey().equals("alinvite") ? "&a[内置]" : "&e[第三方]";
                        sender.sendMessage(ConfigManager.colorize("  " + marker + " &f%" + entry.getKey() + "_*% &7- " + entry.getValue()));
                        count++;
                    }
                    sender.sendMessage(ConfigManager.colorize("&7共 " + count + " 个扩展"));
                }
                sender.sendMessage(ConfigManager.colorize("&6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            }

            case "scan" -> {
                if (args.length >= 4) {
                    String menuName = args[3].toLowerCase();
                    // 映射简化名称
                    String mappedName = switch (menuName) {
                        case "main", "main_menu" -> "main_menu";
                        case "veteran", "veteran_menu" -> "veteran_menu";
                        case "shop", "shop_menu" -> "shop_menu";
                        default -> menuName;
                    };

                    var section = plugin.getMenuManager().getLoader().getMergedRaw().getConfigurationSection(mappedName);
                    if (section == null) {
                        sender.sendMessage(ConfigManager.colorize("&c菜单不存在: " + mappedName));
                        sender.sendMessage(ConfigManager.colorize("&7可用菜单: main_menu, veteran_menu, shop_menu"));
                        return;
                    }

                    PapiDetector.ScanResult result = PapiDetector.scanMenu(section, mappedName);
                    sender.sendMessage(ConfigManager.colorize("&6━━━━━━ &e菜单扫描: " + mappedName + " &6━━━━━━"));
                    printScanResult(sender, result);
                } else {
                    // 扫描所有菜单
                    sender.sendMessage(ConfigManager.colorize("&6━━━━━━ &e扫描所有菜单 &6━━━━━━"));
                    var results = PapiDetector.scanAllMenus(plugin.getMenuManager().getLoader().getMergedRaw());
                    int totalThirdParty = 0;
                    for (java.util.Map.Entry<String, PapiDetector.ScanResult> entry : results.entrySet()) {
                        sender.sendMessage(ConfigManager.colorize("&e▸ " + entry.getKey() + ":"));
                        printScanResult(sender, entry.getValue());
                        totalThirdParty += entry.getValue().getThirdPartyCount();
                    }
                    if (totalThirdParty == 0) {
                        sender.sendMessage(ConfigManager.colorize("&7  所有菜单中未发现第三方 PAPI 占位符"));
                    }
                    sender.sendMessage(ConfigManager.colorize("&6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                }
            }

            case "test" -> {
                if (args.length < 5) {
                    sender.sendMessage(ConfigManager.colorize("&c用法: /alinvite admin papi test <玩家> <占位符>"));
                    sender.sendMessage(ConfigManager.colorize("&7示例: /alinvite admin papi test Steve %luckperms_prefix%"));
                    return;
                }

                String targetPlayer = args[3];
                String placeholder = args[4];
                String result = PapiDetector.testPlaceholder(placeholder, targetPlayer);
                sender.sendMessage(ConfigManager.colorize("&6[PAPI测试] &f占位符: &e" + placeholder));
                sender.sendMessage(result);
            }

            default -> sendPapiHelp(sender);
        }
    }

    /**
     * 发送 PAPI 管理命令帮助
     */
    private void sendPapiHelp(CommandSender sender) {
        sender.sendMessage(ConfigManager.colorize("&6━━━━━━ &ePlaceholderAPI 检测工具 &6━━━━━━"));
        sender.sendMessage(ConfigManager.colorize("&e/alinvite admin papi list &7- 列出所有已注册的PAPI扩展"));
        sender.sendMessage(ConfigManager.colorize("&e/alinvite admin papi scan [菜单] &7- 扫描菜单中的PAPI占位符"));
        sender.sendMessage(ConfigManager.colorize("&e/alinvite admin papi test <玩家> <占位符> &7- 测试占位符解析"));
        sender.sendMessage(ConfigManager.colorize("&7示例:"));
        sender.sendMessage(ConfigManager.colorize("&7  /alinvite admin papi scan main_menu"));
        sender.sendMessage(ConfigManager.colorize("&7  /alinvite admin papi test Steve %luckperms_prefix%"));
        sender.sendMessage(ConfigManager.colorize("&6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    /**
     * 打印扫描结果
     */
    private void printScanResult(CommandSender sender, PapiDetector.ScanResult result) {
        if (!result.alinvitePlaceholders.isEmpty()) {
            sender.sendMessage(ConfigManager.colorize("  &a[ALInvite内置] &7(" + result.alinvitePlaceholders.size() + "个):"));
            for (PapiDetector.PlaceholderEntry entry : result.alinvitePlaceholders) {
                sender.sendMessage(ConfigManager.colorize("    &7- " + entry.placeholder));
            }
        }
        if (!result.thirdPartyPlaceholders.isEmpty()) {
            sender.sendMessage(ConfigManager.colorize("  &e[第三方] &7(" + result.thirdPartyPlaceholders.size() + "个):"));
            for (PapiDetector.PlaceholderEntry entry : result.thirdPartyPlaceholders) {
                sender.sendMessage(ConfigManager.colorize("    &e- " + entry.placeholder + " &7(" + entry.expansionName + ")"));
            }
        }
        if (result.getTotalCount() == 0) {
            sender.sendMessage(ConfigManager.colorize("  &7未发现任何 PAPI 占位符"));
        }
    }

    /**
     * 处理玩家查询贡献返点
     */
    private void handleContribution(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("errors.player_only"));
            return;
        }
        
        if (!player.hasPermission("alinvite.use")) {
            player.sendMessage(plugin.getConfigManager().getMessage("errors.no_permission"));
            return;
        }
        
        // 检查是否拥有贡献返点权限
        String contributionPermission = plugin.getConfigManager().getConfig()
            .getString("contribution_rebate.permission", "alinvite.rebate.contribution");
        
        if (!player.hasPermission(contributionPermission)) {
            player.sendMessage("§c您没有贡献返点权限");
            return;
        }
        
        plugin.getDatabaseManager().getContributionAmount(player.getUniqueId()).thenAccept(amount -> {
            String message = "§a您的贡献返点余额: §e" + amount + " §a点券";
            player.sendMessage(message);
            player.sendMessage("§7提示: 贡献返点用于后期兑换，不会自动发放点券");
        });
    }
}
