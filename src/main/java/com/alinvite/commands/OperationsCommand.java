package com.alinvite.commands;

import com.alinvite.ALInvite;
import com.alinvite.database.delivery.DeliveryDao;
import com.alinvite.manager.PlaytimeManager;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Operational commands share one permission boundary and never derive offline UUIDs from names. */
public final class OperationsCommand {
    private static final Set<String> OWN = Set.of("status", "pending", "cancel", "deliveries");
    private static final Set<String> ADMIN = Set.of("status", "pending", "cancel", "deliveries", "delivery");
    private final ALInvite plugin;
    public OperationsCommand(ALInvite plugin) { this.plugin = plugin; }

    public boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        boolean admin = "admin".equalsIgnoreCase(args[0]);
        int offset = admin ? 1 : 0;
        if (args.length <= offset) return false;
        String operation = args[offset].toLowerCase(Locale.ROOT);
        if (!(admin ? ADMIN : OWN).contains(operation)) return false;
        Runnable dispatch = () -> dispatch(sender, args, admin, offset, operation);
        if (sender instanceof Player player) plugin.getScheduler().runAtPlayer(player, dispatch);
        else dispatch.run();
        return true;
    }

    private void dispatch(CommandSender sender, String[] args, boolean admin, int offset, String operation) {
        Player player = sender instanceof Player p ? p : null;
        boolean locked = player != null && plugin.getBindingGateManager().isLocked(player.getUniqueId());
        String permission = "status".equals(operation) ? "alinvite.status"
            : "pending".equals(operation) || "cancel".equals(operation) ? "alinvite.pending" : "alinvite.delivery";
        if (!OperationAccess.allowed(admin, player != null,
                sender.hasPermission("alinvite.use") && sender.hasPermission(permission),
                sender.hasPermission("alinvite.admin"), locked, "cancel".equals(operation) || "delivery".equals(operation))) {
            message(sender, "errors.no_permission");
            return;
        }
        if ("delivery".equals(operation)) {
            resolveDelivery(sender, args, offset);
            return;
        }
        if ("cancel".equals(operation)) {
            if (args.length <= offset + 1) { message(sender, "operations.usage"); return; }
            String target = args[offset + 1].toLowerCase(Locale.ROOT);
            if (!target.matches("[a-z0-9_]{3,16}")) { message(sender, "commands.add.invalid_name"); return; }
            UUID owner = admin ? null : player.getUniqueId();
            plugin.getDatabaseManager().cancelPendingInvite(target, owner).thenAccept(changed ->
                message(sender, changed ? "operations.cancelled" : "operations.not_found"))
                .exceptionally(error -> failure(sender, error));
            return;
        }
        CompletableFuture<UUID> target;
        if (!admin) target = CompletableFuture.completedFuture(player.getUniqueId());
        else if (args.length > offset + 1) target = resolveUuid(args[offset + 1]);
        else if ("pending".equals(operation) || "deliveries".equals(operation)) target = CompletableFuture.completedFuture(null);
        else { message(sender, "operations.usage"); return; }
        target.thenCompose(uuid -> switch (operation) {
            case "status" -> status(sender, uuid);
            case "pending" -> pending(sender, uuid);
            case "deliveries" -> deliveries(sender, uuid);
            default -> CompletableFuture.completedFuture(null);
        }).exceptionally(error -> failure(sender, error));
    }

    private CompletableFuture<UUID> resolveUuid(String value) {
        try { return CompletableFuture.completedFuture(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { }
        Player online = Bukkit.getPlayerExact(value);
        if (online != null) return CompletableFuture.completedFuture(online.getUniqueId());
        return plugin.getDatabaseManager().findBoundPlayerByName(value.toLowerCase(Locale.ROOT))
            .thenApply(uuid -> {
                if (uuid == null) throw new IllegalArgumentException("Unknown player; use backend UUID");
                return uuid;
            });
    }

    private CompletableFuture<Void> status(CommandSender sender, UUID uuid) {
        if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("UUID required"));
        return plugin.getPlaytimeManager().refresh(uuid).thenCompose(ignored ->
            plugin.getDatabaseManager().getQuotaStatus(uuid)).thenAccept(status -> {
                message(sender, "operations.status", "{uuid}", uuid.toString(),
                    "{group}", status.group(), "{mode}", status.mode(), "{interval}", status.interval(),
                    "{time}", PlaytimeManager.format(status.totalMillis()),
                    "{available}", Integer.toString(status.available()), "{reserved}", Integer.toString(status.reserved()),
                    "{cap}", status.capacity(), "{next}", status.next(), "{remaining}", status.remaining());
                message(sender, "operations." + status.state());
            });
    }

    private CompletableFuture<Void> pending(CommandSender sender, UUID uuid) {
        return plugin.getDatabaseManager().listPendingInvites(uuid, 50).thenAccept(rows -> {
            message(sender, "operations.pending_header", "{count}", Integer.toString(rows.size()));
            for (var row : rows) message(sender, "operations.pending_row", "{target}", row.displayName(),
                "{owner}", row.inviterUuid().toString(), "{created}", time(row.createdAt()),
                "{expires}", row.expiresAt() == null ? text("operations.never") : time(row.expiresAt()));
        });
    }

    private CompletableFuture<Void> deliveries(CommandSender sender, UUID uuid) {
        return io(() -> plugin.getDatabaseManager().getDeliveryDao().list(uuid, 50)).thenAccept(rows -> {
            message(sender, "operations.deliveries_header", "{count}", Integer.toString(rows.size()));
            for (DeliveryDao.Work work : rows) message(sender, "operations.delivery_row", "{id}", work.id(),
                "{state}", String.valueOf(work.state()), "{kind}", work.kind(),
                "{attempts}", Integer.toString(work.attempts()), "{detail}", safe(work.lastError()));
        });
    }

    private void resolveDelivery(CommandSender sender, String[] args, int offset) {
        if (args.length <= offset + 2) { message(sender, "operations.usage"); return; }
        String action = args[offset + 1].toLowerCase(Locale.ROOT), id = args[offset + 2];
        if ("show".equals(action)) {
            io(() -> plugin.getDatabaseManager().getDeliveryDao().find(id)).thenAccept(work -> {
                if (work == null) { message(sender, "operations.not_found"); return; }
                message(sender, "operations.delivery_detail", "{id}", work.id(), "{recipient}", work.recipientUuid().toString(),
                    "{state}", String.valueOf(work.state()), "{payload}", safe(work.payload()), "{detail}", safe(work.lastError()));
            }).exceptionally(error -> failure(sender, error));
            return;
        }
        if ((!"retry".equals(action) && !"done".equals(action)) || args.length <= offset + 3) {
            message(sender, "operations.usage"); return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, offset + 3, args.length));
        String actor = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        io(() -> plugin.getDatabaseManager().getDeliveryDao().resolve(id, "retry".equals(action), actor, reason))
            .thenAccept(changed -> message(sender, changed ? "operations.resolved" : "operations.resolve_rejected"))
            .exceptionally(error -> failure(sender, error));
    }

    private String time(long millis) {
        ZoneId zone;
        try { zone = ZoneId.of(plugin.getConfigManager().getConfig().getString("time.timezone", "Asia/Shanghai")); }
        catch (RuntimeException invalid) { zone = ZoneId.of("UTC"); }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(zone).format(Instant.ofEpochMilli(millis));
    }
    private static String safe(String value) {
        return value == null ? "-" : value.replace('\n', ' ').replace('\r', ' ').replace('§', '?');
    }
    private String text(String key) { return plugin.getConfigManager().getMessage(key); }
    private void message(CommandSender sender, String key, String... replacements) {
        Runnable output = () -> {
            String message = text(key);
            for (int i = 0; i < replacements.length; i += 2) message = message.replace(replacements[i], replacements[i + 1]);
            sender.sendMessage(message);
        };
        if (sender instanceof Player player) plugin.getScheduler().runAtPlayer(player, output);
        else plugin.getScheduler().runGlobal(output);
    }
    private Void failure(CommandSender sender, Throwable error) {
        plugin.getLogger().warning("邀请管理查询失败: " + error.getMessage());
        message(sender, "operations.failed");
        return null;
    }
    private <T> CompletableFuture<T> io(Callable<T> action) {
        return AsyncPool.supply(() -> {
            try { return action.call(); }
            catch (Exception error) { throw new CompletionException(error); }
        });
    }
}
