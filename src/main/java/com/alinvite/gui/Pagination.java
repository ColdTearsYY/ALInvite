package com.alinvite.gui;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分页纯函数。页码从 1 开始（对外展示），存储层仍可用 0 基由调用方换算。
 * 纯逻辑类，无 Bukkit 依赖，可单测。
 */
public final class Pagination {

    private Pagination() {
    }

    /**
     * @param entries  全量条目
     * @param pageMap  玩家页码存储（1 基），方法内会做夹取并回写
     * @param playerId 玩家
     * @param pageSize 动态区槽位数
     */
    public static <T> Page<T> page(List<T> entries, Map<UUID, Integer> pageMap, UUID playerId, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        List<T> safeEntries = entries == null ? List.of() : entries;
        int totalPages = Math.max(1, (safeEntries.size() + pageSize - 1) / pageSize);
        int currentPage = Math.max(1, Math.min(pageMap.getOrDefault(playerId, 1), totalPages));
        pageMap.put(playerId, currentPage);
        int from = Math.min((currentPage - 1) * pageSize, safeEntries.size());
        int to = Math.min(from + pageSize, safeEntries.size());
        return new Page<>(List.copyOf(safeEntries.subList(from, to)), currentPage, totalPages);
    }

    public record Page<T>(List<T> entries, int currentPage, int totalPages) {
    }
}
