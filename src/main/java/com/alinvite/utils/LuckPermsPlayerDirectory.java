package com.alinvite.utils;

import com.alinvite.database.PlayerNameDirectory;
import net.luckperms.api.LuckPermsProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Async lookups against the shared LuckPerms user store, including users offline on this backend. */
public final class LuckPermsPlayerDirectory {
    private static final int LOOKUP_BATCH_SIZE = 64;

    private LuckPermsPlayerDirectory() { }

    public static CompletableFuture<UUID> lookupUniqueId(String username) {
        try {
            return LuckPermsProvider.get().getUserManager().lookupUniqueId(username)
                .orTimeout(10, TimeUnit.SECONDS);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public static CompletableFuture<String> lookupUsername(UUID uuid) {
        try {
            return LuckPermsProvider.get().getUserManager().lookupUsername(uuid)
                .orTimeout(10, TimeUnit.SECONDS);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /** Enumerates stored LP users in bounded batches; no Bukkit local-player lookup is used. */
    public static CompletableFuture<List<PlayerNameDirectory.PlayerIdentity>> loadKnownUsers() {
        try {
            var users = LuckPermsProvider.get().getUserManager();
            return users.getUniqueUsers().orTimeout(30, TimeUnit.SECONDS).thenCompose(ids -> {
                List<UUID> allIds = new ArrayList<>(ids);
                CompletableFuture<List<PlayerNameDirectory.PlayerIdentity>> result =
                    CompletableFuture.completedFuture(new ArrayList<>());
                for (int start = 0; start < allIds.size(); start += LOOKUP_BATCH_SIZE) {
                    List<UUID> batch = allIds.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, allIds.size()));
                    result = result.thenCompose(accumulated -> {
                        List<CompletableFuture<PlayerNameDirectory.PlayerIdentity>> lookups = new ArrayList<>(batch.size());
                        for (UUID uuid : batch) {
                            lookups.add(users.lookupUsername(uuid)
                                .orTimeout(10, TimeUnit.SECONDS)
                                .thenApply(name -> name == null || name.isBlank()
                                    ? null : new PlayerNameDirectory.PlayerIdentity(uuid, name))
                                .exceptionally(error -> null));
                        }
                        CompletableFuture<?>[] futures = lookups.toArray(CompletableFuture[]::new);
                        return CompletableFuture.allOf(futures).thenApply(ignored -> {
                            lookups.stream().map(CompletableFuture::join)
                                .filter(java.util.Objects::nonNull).forEach(accumulated::add);
                            return accumulated;
                        });
                    });
                }
                return result.thenApply(List::copyOf);
            });
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
