package com.alinvite.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InviteQuotaPolicyTest {

    @Test
    void onceModeOnlyGrantsOneCompletedPeriod() {
        assertEquals(0, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.ONCE, 23.99, 24));
        assertEquals(1, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.ONCE, 24, 24));
        assertEquals(1, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.ONCE, 240, 24));
    }

    @Test
    void recurringModeCountsEveryCompletedPeriod() {
        assertEquals(0, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.RECURRING, 23.99, 24));
        assertEquals(1, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.RECURRING, 24, 24));
        assertEquals(2, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.RECURRING, 48.5, 24));
    }

    @Test
    void invalidConfigurationDoesNotGrantSlots() {
        assertEquals(0, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.RECURRING, -1, 24));
        assertEquals(0, InviteQuotaPolicy.eligiblePeriods(InviteQuotaPolicy.Mode.RECURRING, 24, 0));
        assertEquals(InviteQuotaPolicy.Mode.ONCE, InviteQuotaPolicy.Mode.fromConfig("invalid"));
    }
}
