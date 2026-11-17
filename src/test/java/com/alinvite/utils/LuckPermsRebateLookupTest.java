package com.alinvite.utils;

import com.alinvite.manager.RebateRatePolicy;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LuckPermsRebateLookupTest {
    private final UUID uuid = UUID.randomUUID();
    private final RebateRatePolicy policy = new RebateRatePolicy(0.05,
        List.of(new RebateRatePolicy.Tier("alinvite.rebate.vip", 0.16, 2)));

    @Test void loadsUncachedUuidUsesStaticContextAuditsExpiryAndCleansUp() {
        AtomicBoolean audited = new AtomicBoolean();
        AtomicInteger cleanups = new AtomicInteger();
        var options = proxy(QueryOptions.class, (p, m, a) -> null);
        var permissions = proxy(CachedPermissionData.class, (p, m, a) -> {
            assertEquals("checkPermission", m.getName());
            assertTrue(audited.get(), "temporary memberships must be audited before calculation");
            assertEquals("alinvite.rebate.vip", a[0]);
            return Tristate.TRUE;
        });
        var cachedData = proxy(CachedDataManager.class, (p, m, a) -> {
            assertEquals("getPermissionData", m.getName());
            assertSame(options, a[0]);
            return permissions;
        });
        var user = proxy(User.class, (p, m, a) -> switch (m.getName()) {
            case "auditTemporaryNodes" -> { audited.set(true); yield null; }
            case "getCachedData" -> cachedData;
            default -> throw new AssertionError("Unexpected user call " + m);
        });
        var loaded = new CompletableFuture<User>();
        var manager = proxy(UserManager.class, (p, m, a) -> switch (m.getName()) {
            case "loadUser" -> { assertEquals(uuid, a[0]); yield loaded; }
            case "cleanupUser" -> { assertSame(user, a[0]); cleanups.incrementAndGet(); yield null; }
            default -> throw new AssertionError("Must load persisted user, not rely on getUser cache: " + m);
        });
        var contexts = proxy(ContextManager.class, (p, m, a) -> {
            assertEquals("getStaticQueryOptions", m.getName());
            return options;
        });
        var api = api(manager, contexts);
        var rate = LuckPermsRebateLookup.rate(api, uuid, policy);
        assertFalse(rate.isDone());
        loaded.complete(user);
        assertEquals(0.16, rate.join());
        assertEquals(1, cleanups.get());
    }

    @Test void falseAndUndefinedPermissionsBothUseBase() {
        for (var state : List.of(Tristate.FALSE, Tristate.UNDEFINED)) {
            var permissions = proxy(CachedPermissionData.class, (p, m, a) -> state);
            var cached = proxy(CachedDataManager.class, (p, m, a) -> permissions);
            var user = proxy(User.class, (p, m, a) -> m.getName().equals("getCachedData") ? cached : null);
            var manager = proxy(UserManager.class, (p, m, a) -> m.getName().equals("loadUser")
                ? CompletableFuture.completedFuture(user) : null);
            var contexts = proxy(ContextManager.class, (p, m, a) -> null);
            assertEquals(0.05, LuckPermsRebateLookup.rate(api(manager, contexts), uuid, policy).join());
        }
    }

    @Test void storageFailurePropagatesAndPermissionFailureStillCleansUp() {
        var failedManager = proxy(UserManager.class, (p, m, a) -> CompletableFuture.failedFuture(new IllegalStateException("storage down")));
        assertThrows(CompletionException.class, () -> LuckPermsRebateLookup.rate(api(failedManager, null), uuid, policy).join());
        var cleanups = new AtomicInteger();
        var user = proxy(User.class, (p, m, a) -> {
            if (m.getName().equals("getCachedData")) throw new IllegalStateException("permission failure");
            return null;
        });
        var manager = proxy(UserManager.class, (p, m, a) -> {
            if (m.getName().equals("loadUser")) return CompletableFuture.completedFuture(user);
            if (m.getName().equals("cleanupUser")) cleanups.incrementAndGet();
            return null;
        });
        var contexts = proxy(ContextManager.class, (p, m, a) -> null);
        assertThrows(CompletionException.class, () -> LuckPermsRebateLookup.rate(api(manager, contexts), uuid, policy).join());
        assertEquals(1, cleanups.get());
    }

    private static LuckPerms api(UserManager manager, ContextManager contexts) {
        return proxy(LuckPerms.class, (p, m, a) -> switch (m.getName()) {
            case "getUserManager" -> manager;
            case "getContextManager" -> contexts;
            default -> throw new AssertionError(m);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
