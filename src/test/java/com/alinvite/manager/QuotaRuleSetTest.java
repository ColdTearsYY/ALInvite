package com.alinvite.manager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rule-resolution and boundary tests; no Bukkit, no database. */
class QuotaRuleSetTest {

    private static final QuotaRule DEFAULT = QuotaRule.recurring("default", 48.0, 1, 0);
    private static final QuotaRule VIP1 = QuotaRule.recurring("vip1", 24.0, 2, 10);
    private static final QuotaRule VIP2 = QuotaRule.recurring("vip2", 12.0, 3, 20);

    private final QuotaRuleSet rules = QuotaRuleSet.of(List.of(DEFAULT, VIP1, VIP2), "default");

    @Test
    void matchesRealGroupNamesCaseInsensitively() {
        assertEquals("vip1", rules.resolve(Set.of("vip1")).group());
        assertEquals("vip1", rules.resolve(Set.of("VIP1")).group());
    }

    @Test
    void highestPriorityWinsWhenSeveralGroupsMatch() {
        assertEquals("vip2", rules.resolve(Set.of("default", "vip1", "vip2")).group());
        assertEquals("vip1", rules.resolve(Set.of("default", "vip1")).group());
    }

    @Test
    void fallsBackWhenNothingMatches() {
        assertEquals("default", rules.resolve(Set.of()).group());
        assertEquals("default", rules.resolve(Set.of("some_unknown_group")).group());
        assertEquals("default", rules.resolve(null).group());
    }

    @Test
    void policyKeyChangesWithIntervalOrModeButNotWithCap() {
        assertNotEquals(QuotaRule.recurring("default", 48.0, 1, 0).key(),
            QuotaRule.recurring("default", 24.0, 1, 0).key());
        assertNotEquals(QuotaRule.recurring("default", 48.0, 1, 0).key(),
            new QuotaRule("default", InviteQuotaPolicy.Mode.ONCE, 48.0, 1, 0).key());
        // Tuning the cap must not look like a policy switch (no rebaseline).
        assertEquals(QuotaRule.recurring("default", 48.0, 1, 0).key(),
            QuotaRule.recurring("default", 48.0, 5, 99).key());
    }

    @Test
    void previousPolicyStillResolvableByKey() {
        assertEquals(VIP1.key(), rules.byKey(VIP1.key()).key());
        assertEquals("removed-group", rules.byKey("removed-group@24.00:RECURRING").group());
        assertEquals(24.0, rules.byKey("removed-group@24.00:RECURRING").hoursPerSlot());
        assertEquals("default", rules.byKey(null).group());
    }

    @Test
    void boundaryMathForDefaultAndVipIntervals() {
        // default: 48h boundaries
        assertEquals(0, InviteQuotaPolicy.boundaryIndex(0.0, 48.0));
        assertEquals(0, InviteQuotaPolicy.boundaryIndex(47.99, 48.0));
        assertEquals(1, InviteQuotaPolicy.boundaryIndex(48.0, 48.0));
        assertEquals(2, InviteQuotaPolicy.boundaryIndex(96.0, 48.0));
        assertEquals(4, InviteQuotaPolicy.boundaryIndex(200.0, 48.0));

        // vip1: 24h boundaries
        assertEquals(1, InviteQuotaPolicy.boundaryIndex(24.0, 24.0));
        assertEquals(2, InviteQuotaPolicy.boundaryIndex(48.0, 24.0));
        assertEquals(3, InviteQuotaPolicy.boundaryIndex(72.0, 24.0));
    }

    @Test
    void firstGrantableBoundaryNeverReplaysHistory() {
        assertEquals(1, InviteQuotaPolicy.firstGrantableBoundary(0.0, 48.0));
        assertEquals(2, InviteQuotaPolicy.firstGrantableBoundary(50.0, 48.0));
        assertEquals(3, InviteQuotaPolicy.firstGrantableBoundary(48.0, 24.0));
        assertEquals(6, InviteQuotaPolicy.firstGrantableBoundary(120.0, 24.0));
    }

    @Test
    void reachedRespectsFloatingPointTolerance() {
        assertTrue(InviteQuotaPolicy.reached(1, 48.0, 48.0));
        assertTrue(InviteQuotaPolicy.reached(2, 96.0, 48.0));
        assertTrue(!InviteQuotaPolicy.reached(2, 95.9, 48.0));
        assertTrue(!InviteQuotaPolicy.reached(0, 100.0, 48.0));
    }

    @Test
    void onceModeStillLimitedToOnePeriod() {
        assertEquals(1, InviteQuotaPolicy.eligiblePeriods(
            InviteQuotaPolicy.Mode.ONCE, 500.0, 24.0));
        assertEquals(20, InviteQuotaPolicy.eligiblePeriods(
            InviteQuotaPolicy.Mode.RECURRING, 500.0, 24.0));
    }

    @Test
    void invalidInputDegradesSafely() {
        assertEquals(0, InviteQuotaPolicy.boundaryIndex(-1.0, 48.0));
        assertEquals(0, InviteQuotaPolicy.boundaryIndex(10.0, 0.0));
        assertEquals(0, InviteQuotaPolicy.eligiblePeriods(
            InviteQuotaPolicy.Mode.RECURRING, Double.NaN, 48.0));
        assertEquals("default", QuotaRuleSet.of(List.of(), null).fallback().group());
    }
}
