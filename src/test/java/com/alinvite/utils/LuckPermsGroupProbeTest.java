package com.alinvite.utils;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.query.QueryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LuckPermsGroupProbeTest {
    @AfterEach
    void unregister() throws Exception {
        Method method = LuckPermsProvider.class.getDeclaredMethod("unregister");
        method.setAccessible(true);
        method.invoke(null);
    }

    @Test
    void readsActualInheritedCollectionAndRecoversAfterTransientFailure() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false);
        Group sponsor = proxy(Group.class, (p, m, args) -> {
            if (m.getName().equals("getName")) return "vip1";
            return null;
        });
        QueryOptions options = proxy(QueryOptions.class, (p, m, args) -> null);
        User user = proxy(User.class, (p, m, args) -> switch (m.getName()) {
            case "getPrimaryGroup" -> "default";
            case "getQueryOptions" -> options;
            case "getInheritedGroups" -> {
                if (fail.get()) throw new IllegalStateException("temporary failure");
                yield List.of(sponsor);
            }
            default -> null;
        });
        UserManager manager = proxy(UserManager.class, (p, m, args) -> {
            if (m.getName().equals("getUser")) return user;
            if (m.getName().equals("loadUser")) fail("Must never load storage on entity thread");
            return null;
        });
        LuckPerms api = proxy(LuckPerms.class, (p, m, args) ->
            m.getName().equals("getUserManager") ? manager : null);
        Method register = LuckPermsProvider.class.getDeclaredMethod("register", LuckPerms.class);
        register.setAccessible(true);
        register.invoke(null, api);

        UUID uuid = UUID.randomUUID();
        assertEquals(Set.of("default", "vip1"), LuckPermsGroupProbe.groups(uuid));
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> LuckPermsGroupProbe.groups(uuid));
        fail.set(false);
        assertEquals(Set.of("default", "vip1"), LuckPermsGroupProbe.groups(uuid));
    }

    @Test
    void unreadyProviderSkipsInsteadOfSilentlyDowngradingVip() {
        assertThrows(IllegalStateException.class, () -> LuckPermsGroupProbe.groups(UUID.randomUUID()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
