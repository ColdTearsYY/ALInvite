package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.alinvite.database.DatabaseManager;
import com.alinvite.database.ledger.InviteLedgerDao;
import com.alinvite.utils.AsyncPool;
import com.alinvite.utils.LuckPermsGroupProbe;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the player's real LuckPerms group on the entity scheduler, then flushes the built-in
 * online-time ledger and settles invitation quota asynchronously. Independent of auto_veteran.
 */
public class InviteQuotaManager {

    private final ALInvite plugin;
    private volatile QuotaRuleSet rules;
    private volatile boolean enabled;
    private int taskId = -1;
    private final java.util.Map<UUID, java.util.concurrent.CompletableFuture<Void>> pendingTicks = new java.util.HashMap<>();

    public InviteQuotaManager(ALInvite plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        this.enabled = plugin.getConfigManager().getConfig().getBoolean("invite_quota.enabled", true);
        this.rules = readRules();
        plugin.getDatabaseManager().getAtomicLedger().configure(rules, enabled);
        plugin.getLogger().info("邀请码发放规则: " + describe(rules));
    }

    private QuotaRuleSet readRules() {
        ConfigurationSection section = plugin.getConfigManager().getConfig()
            .getConfigurationSection("invite_quota.groups");
        List<QuotaRule> parsed = new ArrayList<>();
        if (section != null) {
            for (String group : section.getKeys(false)) {
                ConfigurationSection node = section.getConfigurationSection(group);
                if (node == null) {
                    continue;
                }
                parsed.add(new QuotaRule(
                    group,
                    InviteQuotaPolicy.Mode.fromConfig(node.getString("mode", "RECURRING")),
                    node.getDouble("hours_per_slot", 48.0),
                    node.getInt("max_codes", 1),
                    node.getInt("priority", 0)));
            }
        }
        if (parsed.isEmpty()) {
            // Defensive default: the shipped config always defines these two groups.
            parsed.add(QuotaRule.recurring("default", 48.0, 1, 0));
            parsed.add(QuotaRule.recurring("vip1", 24.0, 2, 10));
        }
        String fallbackGroup = plugin.getConfigManager().getConfig()
            .getString("invite_quota.fallback_group", QuotaRule.FALLBACK_GROUP);
        return QuotaRuleSet.of(parsed, fallbackGroup);
    }

    private String describe(QuotaRuleSet set) {
        StringBuilder sb = new StringBuilder();
        for (QuotaRule rule : set.rules()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(rule.group()).append('=').append(rule.hoursPerSlot()).append('h')
                .append("/max").append(rule.maxCodes())
                .append("/p").append(rule.priority());
        }
        sb.append(" (fallback=").append(set.fallback().group()).append(')');
        return sb.toString();
    }

    public QuotaRuleSet rules() {
        return rules;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start() {
        shutdown();
        if (!enabled) {
            plugin.getLogger().info("邀请码发放已禁用（invite_quota.enabled=false）");
            return;
        }
        long intervalSeconds = Math.max(30L, plugin.getConfigManager().getConfig()
            .getInt("invite_quota.check_interval", 300));
        long intervalTicks = intervalSeconds * 20L;
        taskId = plugin.getScheduler().runGlobalTimer(this::tickOnlinePlayers, 100L, intervalTicks);
    }

    public void shutdown() {
        if (taskId != -1) {
            plugin.getScheduler().cancel(taskId);
            taskId = -1;
        }
    }

    private void tickOnlinePlayers() {
        if (!enabled) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickPlayer(player);
        }
    }

    /** Schedules one issuance pass for the player; safe to call from any thread. */
    public void tickPlayer(Player player) {
        refreshPlayer(player).exceptionally(error -> {
            plugin.getLogger().warning("邀请码配额检查失败: " + error.getMessage());
            return null;
        });
    }

    /** Settle the online inviter before consuming inventory, including just-crossed full periods. */
    public java.util.concurrent.CompletableFuture<Void> refreshOnline(UUID uuid) {
        java.util.concurrent.CompletableFuture<Player> player = new java.util.concurrent.CompletableFuture<>();
        plugin.getScheduler().runGlobal(() -> player.complete(Bukkit.getPlayer(uuid)));
        return player.thenCompose(this::refreshPlayer);
    }

    public java.util.concurrent.CompletableFuture<Void> refreshPlayer(Player player) {
        if (!enabled || player == null || !player.isOnline()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        UUID uuid = player.getUniqueId();
        synchronized (pendingTicks) {
            var previous = pendingTicks.getOrDefault(uuid,
                java.util.concurrent.CompletableFuture.completedFuture(null));
            var next = previous.handle((ignored, error) -> null).thenCompose(ignored ->
                plugin.getScheduler().supplyAtPlayer(player, () -> {
                    Set<String> groups = LuckPermsGroupProbe.groups(player);
                    boolean veteran = player.hasPermission(plugin.getConfigManager().getConfig()
                        .getString("invite_code.veteran_permission", "alinvite.veteran"));
                    return new Snapshot(uuid, groups, veteran);
                })).thenCompose(snapshot -> plugin.getPlaytimeManager().refresh(uuid)
                    .thenCompose(millis -> process(uuid, millis / 3600000.0,
                        snapshot.groups(), snapshot.veteranAccess(), player))
                );
            pendingTicks.put(uuid, next);
            next.whenComplete((ignored, error) -> {
                synchronized (pendingTicks) {
                    pendingTicks.remove(uuid, next);
                }
            });
            return next;
        }
    }

    /** Same as {@link #tickPlayer} but for a group change event, where the player is already known. */
    public void onGroupChanged(Player player) {
        tickPlayer(player);
    }

    private java.util.concurrent.CompletableFuture<Void> process(UUID uuid, double hours,
            Set<String> groups, boolean veteranAccess, Player player) {
        QuotaRuleSet currentRules = rules;
        QuotaRule rule = currentRules.resolve(groups);
        return AsyncPool.run(() -> {
            DatabaseManager db = plugin.getDatabaseManager();
            try {
                db.getLedger().ensureEligiblePlayer(uuid, veteranAccess);
                InviteLedgerDao.MigrationResult migration = db.migrateLegacyCodes(uuid, rule, hours);
                if (migration != null
                    && migration.status() == InviteLedgerDao.MigrationStatus.MIGRATED) {
                    plugin.getLogger().info("已迁移玩家 " + uuid + " 的旧邀请码: 旧码="
                        + migration.legacyCode() + " 转换=" + migration.convertedCodes()
                        + " 预留=" + migration.reservedPending());
                }
                InviteLedgerDao.TickResult result = db.applyQuotaTick(uuid, hours, rule, currentRules::byKey);
                if (result == null || result.granted() <= 0) {
                    return;
                }
                plugin.getCacheManager().invalidateInviteCode(uuid);
                plugin.getCacheManager().invalidateStats(uuid);
                if (player == null || !player.isOnline()) {
                    return;
                }
                plugin.getScheduler().runAtPlayer(player, () -> {
                    String message = plugin.getConfigManager()
                        .getMessage("invite_quota.granted", player)
                        .replace("{amount}", String.valueOf(result.granted()));
                    player.sendMessage(message);
                });
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    private record Snapshot(UUID uuid, Set<String> groups, boolean veteranAccess) {
    }
}
