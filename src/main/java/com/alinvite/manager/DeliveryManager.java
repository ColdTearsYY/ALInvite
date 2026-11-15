package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.database.DatabaseManager;
import com.alinvite.database.delivery.DeliveryDao;
import com.alinvite.utils.AsyncPool;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Durable side effects. JDBC on AsyncPool; Bukkit effects on their required scheduler. */
public final class DeliveryManager {
    private static final long LEASE_MS = 60_000L;
    private final ALInvite plugin;
    private final DatabaseManager database;
    private final DeliveryDao dao;
    private final String serverId;
    private final String workerId = UUID.randomUUID().toString();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private int task = -1;
    private int cleanupTask = -1;

    public DeliveryManager(ALInvite plugin) {
        this.plugin = plugin;
        database = plugin.getDatabaseManager();
        dao = database.getDeliveryDao();
        serverId = plugin.getConfigManager().getServerId();
        var config = plugin.getConfigManager().getConfig();
        Map<String, BindingDeliveryPlan.Gift> gifts = new HashMap<>();
        plugin.getGiftManager().getAllGifts().forEach((id, gift) -> gifts.put(id,
            new BindingDeliveryPlan.Gift(snapshot(gift.rewards), Math.max(0L, gift.durationDays) * 86_400_000L)));
        Map<Integer, BindingDeliveryPlan.Milestone> milestones = new HashMap<>();
        plugin.getMilestoneManager().getMilestones().forEach((n, milestone) -> milestones.put(n,
            new BindingDeliveryPlan.Milestone(milestone.name, snapshot(milestone.rewards))));
        BindingDeliveryPlan plan = new BindingDeliveryPlan(dao, database.getTablePrefix(), gifts, milestones,
            config.getString("new_player_reward.default_gift_id", "default"),
            config.getBoolean("new_player_reward.require_gift", false),
            config.getBoolean("milestones.auto_claim", true), config.getBoolean("whitelist_on_bind.enabled", true),
            config.getString("whitelist_on_bind.command", "whitelist add {player}"), serverId,
            config.getString("economy.points_type", "NONE"), config.getString("economy.points_command.give", ""));
        database.setDeliveryPlan(plan);
    }

    private static List<BindingDeliveryPlan.Reward> snapshot(List<MilestoneManager.Reward> rewards) {
        return rewards.stream().map(reward -> new BindingDeliveryPlan.Reward(
            reward.type, String.valueOf(reward.value))).toList();
    }

    public void start() {
        int seconds = Math.max(5, plugin.getConfigManager().getConfig().getInt("delivery.retry_interval_seconds", 15));
        task = plugin.getScheduler().runGlobalTimer(this::tick, 20L, seconds * 20L);
        long cleanup = Math.max(5, plugin.getConfigManager().getConfig().getInt("pre_invites.cleanup_interval_seconds", 30));
        cleanupTask = plugin.getScheduler().runGlobalTimer(() -> {
            if (!closed) io(() -> { database.expirePendingInvitesSync(); return null; })
                .exceptionally(error -> { log(error); return null; });
        }, 20L, cleanup * 20L);
    }

    private void tick() {
        if (closed) return;
        for (Player player : Bukkit.getOnlinePlayers()) pump(player.getUniqueId());
        io(() -> {
            dao.recoverExpired(200);
            // Whitelist registration belongs to the originating backend, and can finish offline.
            for (DeliveryDao.Work work : dao.readyKind("whitelist", 200)) submit(work);
            return null;
        }).exceptionally(error -> { log(error); return null; });
    }

    public void pump(UUID player) {
        if (closed) return;
        io(() -> {
            for (DeliveryDao.Work work : dao.ready(player, 100)) submit(work);
            return null;
        }).exceptionally(error -> { log(error); return null; });
    }

    private void submit(DeliveryDao.Work candidate) {
        if (closed) return;
        BindingDeliveryPlan.Payload payload;
        try { payload = BindingDeliveryPlan.decode(candidate.payload()); }
        catch (RuntimeException malformed) { payload = null; }
        if (payload != null && payload.serverId() != null && !serverId.equals(payload.serverId())) return;
        // Do not take a lease for work that cannot start on this backend. In particular,
        // an offline enqueue followed by shutdown must stay READY, not ambiguous RUNNING.
        if (payload != null && !"native_whitelist".equals(payload.type())
                && Bukkit.getPlayer(candidate.recipientUuid()) == null) return;
        if (!active.add(candidate.id())) return;
        BindingDeliveryPlan.Payload captured = payload;
        io(() -> dao.claim(candidate.id(), workerId, LEASE_MS)).thenCompose(work -> {
            if (work == null) return CompletableFuture.completedFuture(null);
            if (captured == null) return io(() -> dao.uncertain(work.id(), work.claimToken(), "Invalid saved payload"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            return io(() -> !closed && dao.begin(work.id(), work.claimToken(), LEASE_MS)).thenCompose(begun -> {
                if (!begun) return CompletableFuture.completedFuture(null);
                return execute(work, captured, deadline).thenCompose(result -> io(() -> {
                    switch (result.state()) {
                        case DONE -> dao.finish(work.id(), work.claimToken());
                        case RETRY -> dao.defer(work.id(), work.claimToken(), result.detail(), 15_000L);
                        case REVIEW -> dao.uncertain(work.id(), work.claimToken(), result.detail());
                        case EXPAND -> dao.finishAndEnqueue(work.id(), work.claimToken(),
                            BindingDeliveryPlan.rewardJobs(work.id() + ":reward", work.recipientUuid(),
                                captured.rewards(), captured.pointsType(), captured.pointsCommand()));
                    }
                    return null;
                }));
            });
        }).whenComplete((ignored, error) -> {
            active.remove(candidate.id());
            if (error != null) log(error); // Persisted lease recovery handles ambiguous DB acknowledgements.
        });
    }

    enum State { DONE, RETRY, REVIEW, EXPAND }
    record Result(State state, String detail) {
        static Result done() { return new Result(State.DONE, "Confirmed by adapter"); }
        static Result retry(String detail) { return new Result(State.RETRY, detail); }
        static Result review(String detail) { return new Result(State.REVIEW, detail); }
    }

    private CompletableFuture<Result> execute(DeliveryDao.Work work, BindingDeliveryPlan.Payload payload, long deadline) {
        AtomicInteger gate = new AtomicInteger(); // 0 queued, 1 started, 2 abandoned before effects
        Player player = Bukkit.getPlayer(work.recipientUuid());
        if (!"native_whitelist".equals(payload.type()) && player == null)
            return CompletableFuture.completedFuture(Result.retry("Recipient offline on this backend"));
        boolean global = "native_whitelist".equals(payload.type()) || "command".equals(payload.type())
            || "milestone".equals(payload.type())
            || ("points".equals(payload.type()) && "CUSTOM".equals(payload.pointsType()));
        Supplier<Result> action = () -> {
            if (closed || System.nanoTime() >= deadline || !gate.compareAndSet(0, 1))
                return Result.retry("Execution cancelled before side effects");
            try {
                if ("native_whitelist".equals(payload.type())) {
                    OfflinePlayer account = Bukkit.getOfflinePlayer(work.recipientUuid());
                    account.setWhitelisted(true);
                    return account.isWhitelisted() ? Result.done() : Result.retry("Whitelist not persisted");
                }
                if (!global && !player.isOnline()) return Result.retry("Recipient disconnected before execution");
                if (plugin.getBindingGateManager() != null && plugin.getBindingGateManager().isLocked(work.recipientUuid()))
                    return Result.retry("Admission still being resolved");
                return apply(player, payload);
            } catch (Throwable error) {
                return Result.review(error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
            }
        };
        CompletableFuture<Result> future;
        if (global) {
            CompletableFuture<Boolean> admitted = player == null ? CompletableFuture.completedFuture(true)
                : plugin.getScheduler().supplyAtPlayer(player, () -> player.isOnline()
                    && !plugin.getBindingGateManager().isLocked(player.getUniqueId()));
            future = admitted.thenCompose(ready -> {
                if (!ready) return CompletableFuture.completedFuture(Result.retry("Recipient unavailable before command"));
                CompletableFuture<Result> scheduled = new CompletableFuture<>();
                plugin.getScheduler().runGlobal(() -> scheduled.complete(action.get()));
                return scheduled;
            });
        } else {
            future = plugin.getScheduler().supplyAtPlayer(player, action);
        }
        return future.orTimeout(25L, TimeUnit.SECONDS).handle((result, error) -> {
            if (error == null) return result;
            return gate.compareAndSet(0, 2) ? Result.retry("Scheduler cancelled before execution")
                : Result.review("Execution result unknown: " + error.getClass().getSimpleName());
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result apply(Player player, BindingDeliveryPlan.Payload payload) throws Exception {
        return switch (payload.type()) {
            case "command" -> command(player, payload.value());
            case "money" -> {
                double amount = Double.parseDouble(payload.value());
                if (!Double.isFinite(amount) || amount <= 0) yield Result.review("Invalid money amount");
                Class economy;
                try { economy = Class.forName("net.milkbowl.vault.economy.Economy"); }
                catch (ClassNotFoundException unavailable) { yield Result.retry("Vault API unavailable"); }
                var registration = Bukkit.getServicesManager().getRegistration(economy);
                if (registration == null) yield Result.retry("Economy provider unavailable");
                Object response = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class)
                    .invoke(registration.getProvider(), player, amount);
                boolean success = Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response));
                yield success ? Result.done() : Result.retry("Economy provider rejected transaction");
            }
            case "points" -> {
                int amount = Integer.parseInt(payload.value());
                if (amount <= 0) yield Result.review("Invalid points amount");
                if ("CUSTOM".equals(payload.pointsType()))
                    yield command(player, payload.pointsCommand().replace("%amount%", Integer.toString(amount)));
                if (!"PLAYERPOINTS".equals(payload.pointsType())) yield Result.review("Points provider disabled in saved reward configuration");
                Object api = plugin.getRewardService().getPlayerPointsAPI();
                if (api == null) yield Result.retry("PlayerPoints unavailable");
                Method give = api.getClass().getMethod("give", UUID.class, int.class);
                Object response = give.invoke(api, player.getUniqueId(), amount);
                if (Boolean.TRUE.equals(response)) yield Result.done();
                if (Boolean.FALSE.equals(response)) yield Result.retry("PlayerPoints rejected transaction");
                yield Result.review("PlayerPoints returned no verifiable result");
            }
            case "item" -> {
                String[] parts = payload.value().trim().split("\\s+");
                if (parts.length != 2) yield Result.review("Invalid item specification");
                Material material = com.alinvite.utils.ItemUtil.parseMaterial(parts[0], null, null);
                int amount = Integer.parseInt(parts[1]);
                if (material == null || material.isAir() || amount < 1 || amount > 2304)
                    yield Result.review("Invalid item or amount");
                ItemStack item = new ItemStack(material, amount);
                int room = 0;
                for (ItemStack slot : player.getInventory().getStorageContents()) {
                    if (slot == null || slot.getType().isAir()) room += material.getMaxStackSize();
                    else if (slot.isSimilar(item)) room += Math.max(0, material.getMaxStackSize() - slot.getAmount());
                }
                if (room < amount) yield Result.retry("Inventory full; no items added");
                yield player.getInventory().addItem(item).isEmpty() ? Result.done()
                    : Result.review("Inventory accepted only part of the reward");
            }
            case "milestone" -> {
                var rewards = payload.rewards().stream().map(reward -> new MilestoneManager.Reward(reward.type(), reward.value())).toList();
                var milestone = new MilestoneManager.Milestone(payload.name(), rewards, List.of());
                var event = new com.alinvite.api.event.MilestoneClaimEvent(player, payload.milestone(), milestone);
                Bukkit.getPluginManager().callEvent(event);
                yield event.isCancelled() ? Result.review("MilestoneClaimEvent cancelled; administrator may retry")
                    : new Result(State.EXPAND, "Milestone authorized");
            }
            default -> Result.review("Unknown saved reward type: " + payload.type());
        };
    }

    private Result command(Player player, String command) {
        if (command == null || command.isBlank()) return Result.review("Empty saved command");
        String resolved = command.replace("%player_name%", player.getName()).replace("%player%", player.getName())
            .replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString());
        // true acknowledges command dispatch only. It is not proof of external business delivery.
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)
            ? Result.done() : Result.review("Command returned false; external effects unknown");
    }

    private <T> CompletableFuture<T> io(Callable<T> operation) {
        return AsyncPool.supply(() -> {
            try { return operation.call(); }
            catch (Exception error) { throw new CompletionException(error); }
        });
    }

    private void log(Throwable error) {
        if (!closed) plugin.getLogger().warning("持久化奖励任务保留待恢复: " + error.getMessage());
    }

    public void shutdown() {
        closed = true;
        if (task != -1) plugin.getScheduler().cancel(task);
        if (cleanupTask != -1) plugin.getScheduler().cancel(cleanupTask);
    }
}
