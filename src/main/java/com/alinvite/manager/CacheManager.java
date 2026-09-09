package com.alinvite.manager;

import com.alinvite.ALInvite;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 玩家数据缓存（Caffeine）。
 * 带加载器的 get(key, loader) 语义供占位符/PAPI 热路径使用：
 * 命中零开销，未命中才回源（允许在 IO 线程阻塞）。
 */
public class CacheManager {

    private final ALInvite plugin;
    private Cache<String, String> inviteCodeCache;
    private Cache<String, Integer> statsCache;
    private Cache<String, String> giftCache;
    private Cache<String, Boolean> ipCheckCache;
    private Cache<String, UUID> inviterCache;
    private Cache<String, Long> giftPurchaseTimeCache;
    private Cache<String, Double> contributionCache;
    private Cache<String, Double> rebateCache;

    public CacheManager(ALInvite plugin) {
        this.plugin = plugin;
        initCaches();
    }

    private void initCaches() {
        int codeTtl = plugin.getConfigManager().getConfig().getInt("performance.cache_invite_code_ttl", 30);
        int statsTtl = plugin.getConfigManager().getConfig().getInt("performance.cache_stats_ttl", 30);
        int giftTtl = plugin.getConfigManager().getConfig().getInt("performance.cache_gift_ttl", 60);

        int cacheSize = calculateOptimalCacheSize();

        plugin.getLogger().info("初始化缓存 - 缓存大小: " + cacheSize);

        inviteCodeCache = newCache(codeTtl, cacheSize);
        statsCache = newCache(statsTtl, cacheSize);
        giftCache = newCache(giftTtl, cacheSize);
        ipCheckCache = newCache(5, Math.max(500, cacheSize / 2));
        inviterCache = newCache(statsTtl, cacheSize);
        giftPurchaseTimeCache = newCache(giftTtl, cacheSize);
        contributionCache = newCache(statsTtl, cacheSize);
        rebateCache = newCache(statsTtl, cacheSize);
    }

    private <K, V> Cache<K, V> newCache(int ttlMinutes, int size) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(size)
                .build();
    }

    private int calculateOptimalCacheSize() {
        int maxPlayers = org.bukkit.Bukkit.getMaxPlayers();
        int baseSize = 1000;
        if (maxPlayers > 100) {
            return Math.max(baseSize, maxPlayers * 3);
        } else if (maxPlayers > 50) {
            return Math.max(baseSize, maxPlayers * 2);
        }
        return baseSize;
    }

    // ─── 邀请码 ───

    public String getInviteCode(UUID uuid) {
        return inviteCodeCache.getIfPresent(uuid.toString());
    }

    public String getInviteCode(UUID uuid, Function<UUID, String> loader) {
        return inviteCodeCache.get(uuid.toString(), key -> loader.apply(uuid));
    }

    public void setInviteCode(UUID uuid, String code) {
        inviteCodeCache.put(uuid.toString(), code);
    }

    public void invalidateInviteCode(UUID uuid) {
        inviteCodeCache.invalidate(uuid.toString());
    }

    // ─── 邀请数 ───

    public Integer getStats(UUID uuid) {
        return statsCache.getIfPresent(uuid.toString());
    }

    public Integer getStats(UUID uuid, Function<UUID, Integer> loader) {
        return statsCache.get(uuid.toString(), key -> loader.apply(uuid));
    }

    public void setStats(UUID uuid, int stats) {
        statsCache.put(uuid.toString(), stats);
    }

    public void invalidateStats(UUID uuid) {
        statsCache.invalidate(uuid.toString());
    }

    // ─── 礼包 ───

    public String getGiftId(UUID uuid) {
        return giftCache.getIfPresent(uuid.toString());
    }

    public String getGiftId(UUID uuid, Function<UUID, String> loader) {
        return giftCache.get(uuid.toString(), key -> loader.apply(uuid));
    }

    public void setGiftId(UUID uuid, String giftId) {
        giftCache.put(uuid.toString(), giftId);
    }

    public void invalidateGiftId(UUID uuid) {
        giftCache.invalidate(uuid.toString());
    }

    public Long getGiftPurchaseTime(UUID uuid, Function<UUID, Long> loader) {
        return giftPurchaseTimeCache.get(uuid.toString(), key -> loader.apply(uuid));
    }

    /** 按礼包 ID 维度的购买时间缓存（key = uuid:giftId），供 PAPI 商店展示使用。 */
    public Long getGiftPurchaseTime(UUID uuid, String giftId, java.util.function.BiFunction<UUID, String, Long> loader) {
        return giftPurchaseTimeCache.get(uuid + ":" + giftId, key -> loader.apply(uuid, giftId));
    }

    public void invalidateGiftPurchaseTime(UUID uuid) {
        giftPurchaseTimeCache.invalidate(uuid.toString());
    }

    // ─── IP 检查 ───

    public Boolean getIpCheckResult(String ip) {
        return ipCheckCache.getIfPresent(ip);
    }

    public void setIpCheckResult(String ip, boolean result) {
        ipCheckCache.put(ip, result);
    }

    // ─── 邀请人 ───

    public UUID getInviter(UUID uuid, Function<UUID, UUID> loader) {
        return inviterCache.get(uuid.toString(), key -> loader.apply(uuid));
    }

    public void invalidateInviter(UUID uuid) {
        inviterCache.invalidate(uuid.toString());
    }

    // ─── 贡献/返点 ───

    public Double getContribution(UUID uuid, Function<UUID, Double> loader) {
        return contributionCache.get(uuid.toString(), key -> loader.apply(uuid));
    }

    public void invalidateContribution(UUID uuid) {
        contributionCache.invalidate(uuid.toString());
    }

    public Double getTotalRebate(UUID uuid, Function<UUID, Double> loader) {
        return rebateCache.get(uuid.toString(), key -> loader.apply(uuid));
    }

    public void invalidateTotalRebate(UUID uuid) {
        rebateCache.invalidate(uuid.toString());
    }

    // ─── 兼容旧 API ───

    public CompletableFuture<String> getInviteCodeAsync(UUID uuid) {
        String cached = getInviteCode(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return plugin.getDatabaseManager().getInviteCodeByPlayer(uuid).thenApply(code -> {
            if (code != null) {
                setInviteCode(uuid, code);
            }
            return code;
        });
    }

    public CompletableFuture<Integer> getStatsAsync(UUID uuid) {
        Integer cached = getStats(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return plugin.getDatabaseManager().getPlayerData(uuid).thenApply(data -> {
            if (data != null) {
                int stats = data.totalInvites;
                setStats(uuid, stats);
                return stats;
            }
            return 0;
        });
    }

    public CompletableFuture<String> getGiftIdAsync(UUID uuid) {
        String cached = getGiftId(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return plugin.getDatabaseManager().getGiftId(uuid).thenApply(giftId -> {
            if (giftId != null) {
                setGiftId(uuid, giftId);
            }
            return giftId;
        });
    }

    public void clear() {
        inviteCodeCache.invalidateAll();
        statsCache.invalidateAll();
        giftCache.invalidateAll();
        ipCheckCache.invalidateAll();
        inviterCache.invalidateAll();
        giftPurchaseTimeCache.invalidateAll();
        contributionCache.invalidateAll();
        rebateCache.invalidateAll();
    }
}
