package com.alinvite.manager;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RebateRatePolicyTest {
    private static YamlConfiguration config() {
        var config = new YamlConfiguration();
        config.set("points_rebate.rebate_rates.base.rate", 0.05);
        config.set("points_rebate.rebate_rates.vip.rate", 0.16);
        config.set("points_rebate.rebate_rates.vip.weight", 2);
        config.set("points_rebate.rebate_rates.svip.rate", 0.20);
        config.set("points_rebate.rebate_rates.svip.weight", 3);
        config.set("points_rebate.rebate_rates.admin.rate", 0.0);
        config.set("points_rebate.rebate_rates.admin.weight", 99);
        return config;
    }

    @Test void baseIsFivePercentWithoutAnyPermission() {
        assertEquals(0.05, RebateRatePolicy.from(config()).select(node -> false));
        assertEquals(0.05, RebateRatePolicy.from(new YamlConfiguration()).baseRate());
    }

    @Test void monthlyTierUsesProductionVipSixteenPercent() {
        var policy = RebateRatePolicy.from(config());
        assertEquals(0.16, policy.select(Set.of("alinvite.rebate.vip")::contains));
        assertEquals(16.0, 100 * policy.select(Set.of("alinvite.rebate.vip")::contains));
    }

    @Test void highestWeightWinsIncludingZeroRateAdmin() {
        var policy = RebateRatePolicy.from(config());
        assertEquals(0.20, policy.select(Set.of("alinvite.rebate.vip", "alinvite.rebate.svip")::contains));
        assertEquals(0.0, policy.select(node -> true));
    }

    @Test void customTierPrefixAndExplicitDenyAreRespected() {
        var config = config();
        config.set("points_rebate.permission_prefix", "custom.rebate");
        config.set("points_rebate.rebate_rates.monthly.rate", 0.17);
        config.set("points_rebate.rebate_rates.monthly.weight", 7);
        Map<String, Boolean> permissions = Map.of("custom.rebate.monthly", true, "custom.rebate.admin", false);
        var policy = RebateRatePolicy.from(config);
        assertEquals(0.17, policy.select(node -> Boolean.TRUE.equals(permissions.get(node))), policy.toString());
    }

    @Test void nextSnapshotSeesConfigReloadWithoutStaleSortedGroupCache() {
        var config = config();
        var old = RebateRatePolicy.from(config);
        config.set("points_rebate.rebate_rates.vip.weight", 100);
        config.set("points_rebate.rebate_rates.vip.rate", 0.18);
        assertEquals(0.0, old.select(node -> true));
        assertEquals(0.18, RebateRatePolicy.from(config).select(node -> true));
    }

    @Test void onlineAndOfflineSameUuidResolveSameMonthlyRate() {
        var uuid = UUID.randomUUID();
        var policy = RebateRatePolicy.from(config());
        AtomicInteger offlineCalls = new AtomicInteger();
        var online = RebateRateResolver.resolve(uuid, policy, id -> {
            assertEquals(uuid, id);
            return CompletableFuture.completedFuture(0.16);
        }, id -> { offlineCalls.incrementAndGet(); return CompletableFuture.completedFuture(0.05); }, error -> fail(error));
        assertEquals(0.16, online.join());
        assertEquals(0, offlineCalls.get());
        var offline = RebateRateResolver.resolve(uuid, policy, id -> CompletableFuture.completedFuture(null), id -> {
            assertEquals(uuid, id);
            return CompletableFuture.completedFuture(policy.select(Set.of("alinvite.rebate.vip")::contains));
        }, error -> fail(error));
        assertEquals(online.join(), offline.join());
    }

    @Test void retiredOnlineEntityFallsBackToPersistedMonthlyPermission() {
        var rate = RebateRateResolver.resolve(UUID.randomUUID(), RebateRatePolicy.from(config()),
            id -> CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("retired")),
            id -> CompletableFuture.completedFuture(0.16), error -> fail(error));
        assertEquals(0.16, rate.join());
    }

    @Test void failedOrUnavailableOfflineProviderUsesBaseAndReportsFailure() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var rate = RebateRateResolver.resolve(UUID.randomUUID(), RebateRatePolicy.from(config()),
            id -> CompletableFuture.completedFuture(null),
            id -> CompletableFuture.failedFuture(new IllegalStateException("storage unavailable")), failure::set);
        assertEquals(0.05, rate.join());
        assertNotNull(failure.get());
    }

    @Test void absentOfflineTierIsBaseAndZeroOnlineRateDoesNotFallBack() {
        var policy = RebateRatePolicy.from(config());
        assertEquals(0.05, RebateRateResolver.resolve(UUID.randomUUID(), policy,
            id -> CompletableFuture.completedFuture(null), id -> CompletableFuture.completedFuture(policy.select(node -> false)),
            error -> fail(error)).join());
        assertEquals(0.0, RebateRateResolver.resolve(UUID.randomUUID(), policy,
            id -> CompletableFuture.completedFuture(0.0), id -> { fail("zero is a valid rate"); return null; },
            error -> fail(error)).join());
    }

    @Test void offlineLoadingIsNonBlockingAndSynchronousProviderFailureIsReported() {
        var pending = new CompletableFuture<Double>();
        var policy = RebateRatePolicy.from(config());
        var rate = RebateRateResolver.resolve(UUID.randomUUID(), policy,
            id -> CompletableFuture.completedFuture(null), id -> pending, error -> fail(error));
        assertFalse(rate.isDone());
        pending.complete(0.16);
        assertEquals(0.16, rate.join());
        var failures = new AtomicInteger();
        assertEquals(0.05, RebateRateResolver.resolve(UUID.randomUUID(), policy,
            id -> CompletableFuture.completedFuture(null), id -> { throw new IllegalStateException("unregistered"); },
            error -> failures.incrementAndGet()).join());
        assertEquals(1, failures.get());
    }
}
