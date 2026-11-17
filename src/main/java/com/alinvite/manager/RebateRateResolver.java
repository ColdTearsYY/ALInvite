package com.alinvite.manager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Online lookup may return null if the entity retired; then resolve persisted permissions. */
public final class RebateRateResolver {
    private RebateRateResolver() {}

    public static CompletableFuture<Double> resolve(UUID uuid, RebateRatePolicy policy,
            Function<UUID, CompletableFuture<Double>> online,
            Function<UUID, CompletableFuture<Double>> offline, Consumer<Throwable> onFailure) {
        return invoke(online, uuid).handle((rate, error) -> error == null ? rate : null)
            .thenCompose(rate -> rate != null ? CompletableFuture.completedFuture(rate) : invoke(offline, uuid))
            .handle((rate, error) -> {
                if (error != null || rate == null) {
                    onFailure.accept(error != null ? error : new IllegalStateException("返点权限查询返回空结果"));
                    return policy.baseRate();
                }
                return rate;
            });
    }

    private static CompletableFuture<Double> invoke(Function<UUID, CompletableFuture<Double>> lookup, UUID uuid) {
        try {
            return lookup.apply(uuid);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
