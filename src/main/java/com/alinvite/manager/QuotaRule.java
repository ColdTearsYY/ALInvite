package com.alinvite.manager;

import java.util.Locale;

/**
 * Issuance rule for one LuckPerms group name. Pure data, no Bukkit dependency.
 *
 * @param group        actual LuckPerms group name, matched case-insensitively
 * @param mode         ONCE (single lifetime grant) or RECURRING (one code per completed interval)
 * @param hoursPerSlot cumulative online hours per boundary
 * @param maxCodes     maximum number of held codes (AVAILABLE + RESERVED); 0 = unlimited
 * @param priority     higher wins when several inherited groups match
 */
public record QuotaRule(String group, InviteQuotaPolicy.Mode mode,
                        double hoursPerSlot, int maxCodes, int priority) {

    public static final String FALLBACK_GROUP = "default";

    public QuotaRule {
        String normalized = group == null ? FALLBACK_GROUP : group.trim().toLowerCase(Locale.ROOT);
        group = normalized.isEmpty() ? FALLBACK_GROUP : normalized;
        mode = mode == null ? InviteQuotaPolicy.Mode.RECURRING : mode;
        if (!Double.isFinite(hoursPerSlot) || hoursPerSlot <= 0) {
            hoursPerSlot = 48.0;
        }
        if (maxCodes < 0) {
            maxCodes = 0;
        }
    }

    public static QuotaRule recurring(String group, double hoursPerSlot, int maxCodes, int priority) {
        return new QuotaRule(group, InviteQuotaPolicy.Mode.RECURRING, hoursPerSlot, maxCodes, priority);
    }

    /**
     * Stable policy identity persisted in the ledger. The interval and mode are part of the key so
     * that changing either one is treated as a policy switch: the previous policy is settled first
     * and the new one is baselined at the player's current boundary (no retroactive replay).
     */
    public String key() {
        return group + "@" + String.format(Locale.ROOT, "%.2f", hoursPerSlot) + ":" + mode.name();
    }

    public boolean unlimited() {
        return maxCodes <= 0;
    }
}
