package com.alinvite.utils;

import com.alinvite.manager.RebateRatePolicy;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.UserManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Isolated optional dependency. Only called when the server has enabled LuckPerms. */
public final class LuckPermsRebateLookup {
    private LuckPermsRebateLookup() {}

    public static CompletableFuture<Double> rate(UUID uuid, RebateRatePolicy policy) {
        return rate(LuckPermsProvider.get(), uuid, policy);
    }

    static CompletableFuture<Double> rate(LuckPerms api, UUID uuid, RebateRatePolicy policy) {
        UserManager manager = api.getUserManager();
        // loadUser also works for users absent from the local backend's loaded-user cache.
        return manager.loadUser(uuid).thenApply(user -> {
            if (user == null) throw new IllegalStateException("LuckPerms 未能加载邀请人 " + uuid);
            try {
                user.auditTemporaryNodes();
                // An offline user has no world/entity context. Retain this backend's static context;
                // never use nonContextual(), which could grant permissions from unrelated servers.
                var options = api.getContextManager().getStaticQueryOptions();
                var permissions = user.getCachedData().getPermissionData(options);
                return policy.select(node -> permissions.checkPermission(node).asBoolean());
            } finally {
                // LuckPerms retains online users and releases users loaded only for this calculation.
                manager.cleanupUser(user);
            }
        });
    }
}
