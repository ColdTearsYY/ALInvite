package com.alinvite.manager;

import java.util.Locale;

/** Pure playtime-to-invite-slot policy used by the automatic veteran checker. */
public final class InviteQuotaPolicy {

    public enum Mode {
        ONCE,
        RECURRING;

        public static Mode fromConfig(String value) {
            if (value == null) {
                return ONCE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ONCE;
            }
        }
    }

    private InviteQuotaPolicy() {
    }

    public static int eligiblePeriods(Mode mode, double playtimeHours, double hoursPerSlot) {
        if (!Double.isFinite(playtimeHours) || playtimeHours < 0
                || !Double.isFinite(hoursPerSlot) || hoursPerSlot <= 0) {
            return 0;
        }
        long completed = (long) Math.floor(playtimeHours / hoursPerSlot);
        if (completed <= 0) {
            return 0;
        }
        if (mode == Mode.ONCE) {
            return 1;
        }
        return completed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) completed;
    }
}
