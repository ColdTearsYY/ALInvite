package com.alinvite.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ordered rule table keyed by real LuckPerms group name.
 *
 * <p>Resolution: the matching group with the highest {@code priority} wins (ties broken by group
 * name for determinism). When nothing matches — no LuckPerms, no group in the table, empty group
 * set — the fallback rule is used. Pure data, no Bukkit dependency.
 */
public final class QuotaRuleSet {

    private final List<QuotaRule> ordered;
    private final Map<String, QuotaRule> byGroup;
    private final Map<String, QuotaRule> byKey;
    private final QuotaRule fallback;

    private QuotaRuleSet(List<QuotaRule> ordered, QuotaRule fallback) {
        this.ordered = List.copyOf(ordered);
        this.fallback = fallback;
        Map<String, QuotaRule> groups = new LinkedHashMap<>();
        Map<String, QuotaRule> keys = new LinkedHashMap<>();
        for (QuotaRule rule : this.ordered) {
            groups.put(rule.group(), rule);
            keys.put(rule.key(), rule);
        }
        this.byGroup = Map.copyOf(groups);
        this.byKey = Map.copyOf(keys);
    }

    /**
     * @param rules         configured rules; duplicates by group keep the first occurrence
     * @param fallbackGroup group name used when nothing matches; a synthetic 48h/1-code rule is
     *                      built when that group is absent
     */
    public static QuotaRuleSet of(Collection<QuotaRule> rules, String fallbackGroup) {
        String wanted = fallbackGroup == null || fallbackGroup.isBlank()
            ? QuotaRule.FALLBACK_GROUP
            : fallbackGroup.trim().toLowerCase(Locale.ROOT);

        Map<String, QuotaRule> unique = new LinkedHashMap<>();
        if (rules != null) {
            for (QuotaRule rule : rules) {
                if (rule == null) {
                    continue;
                }
                unique.putIfAbsent(rule.group(), rule);
            }
        }
        QuotaRule fallback = unique.get(wanted);
        if (fallback == null) {
            fallback = unique.get(QuotaRule.FALLBACK_GROUP);
        }
        if (fallback == null) {
            fallback = QuotaRule.recurring(QuotaRule.FALLBACK_GROUP, 48.0, 1, 0);
        }

        List<QuotaRule> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator
            .comparingInt(QuotaRule::priority).reversed()
            .thenComparing(QuotaRule::group));
        return new QuotaRuleSet(sorted, fallback);
    }

    /** Highest-priority rule whose group name appears in {@code groups}. */
    public QuotaRule resolve(Set<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return fallback;
        }
        Set<String> normalized = new java.util.HashSet<>();
        for (String group : groups) {
            if (group != null && !group.isBlank()) {
                normalized.add(group.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (QuotaRule rule : ordered) {
            if (normalized.contains(rule.group())) {
                return rule;
            }
        }
        return fallback;
    }

    /** Rule that previously produced {@code key}; falls back when the group was removed/renamed. */
    public QuotaRule byKey(String key) {
        if (key == null) {
            return fallback;
        }
        QuotaRule rule = byKey.get(key);
        if (rule != null) {
            return rule;
        }
        // The key persists the old interval/mode, even when config renamed or removed that rule.
        try {
            int at = key.lastIndexOf('@');
            int colon = key.lastIndexOf(':');
            if (at > 0 && colon > at) {
                String group = key.substring(0, at);
                QuotaRule current = byGroup.getOrDefault(group, fallback);
                return new QuotaRule(group, InviteQuotaPolicy.Mode.valueOf(key.substring(colon + 1)),
                    Double.parseDouble(key.substring(at + 1, colon)), current.maxCodes(), current.priority());
            }
        } catch (IllegalArgumentException ignored) {
        }
        return fallback;
    }

    public QuotaRule fallback() {
        return fallback;
    }

    public Collection<QuotaRule> rules() {
        return ordered;
    }
}
