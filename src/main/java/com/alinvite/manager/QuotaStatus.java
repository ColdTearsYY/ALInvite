package com.alinvite.manager;

import com.alinvite.database.ledger.AtomicInviteLedger;
import java.util.Locale;

/** Presentation derived from committed rows, without minting inventory during queries. */
public record QuotaStatus(String group, String mode, String interval, long totalMillis, int available, int reserved,
                          String capacity, String next, String remaining, String state) {
    public static QuotaStatus from(AtomicInviteLedger.Inventory stock, QuotaRuleSet rules, boolean enabled) {
        QuotaRule rule = stock.policyKey() == null ? null : rules.byKey(stock.policyKey());
        if (rule == null || !rule.key().equals(stock.policyKey())) {
            return new QuotaStatus("?", "?", "?", stock.totalMillis(), stock.available(), stock.reserved(),
                "?", "-", "-", stock.registered() ? "unknown_rule" : "unregistered");
        }
        int cap = stock.maxCodes() >= 0 ? stock.maxCodes() : rule.maxCodes();
        boolean full = cap > 0 && stock.available() + stock.reserved() >= cap;
        double hours = stock.totalMillis() / 3600000.0;
        boolean once = rule.mode() == InviteQuotaPolicy.Mode.ONCE;
        boolean ended = once && (stock.nextBoundary() > 1 || hours >= rule.hoursPerSlot() && full);
        long boundary = Math.max(1, stock.nextBoundary());
        if (full && !once) boundary = Math.max(boundary, (long) Math.floor(hours / rule.hoursPerSlot()) + 1L);
        double nextHours = boundary * rule.hoursPerSlot();
        String state = !enabled ? "disabled" : ended ? "once_finished" : full ? "full"
            : hours >= nextHours ? "awaiting_settlement" : "progress";
        String next = ended || !enabled ? "-" : String.format(Locale.ROOT, "%.2fh", nextHours);
        String remaining = ended || !enabled ? "-" : String.format(Locale.ROOT, "%.2fh", Math.max(0, nextHours - hours));
        return new QuotaStatus(rule.group(), rule.mode().name(), String.format(Locale.ROOT, "%.2fh", rule.hoursPerSlot()), stock.totalMillis(), stock.available(), stock.reserved(),
            cap == 0 ? "∞" : Integer.toString(cap), next, remaining, state);
    }
}
