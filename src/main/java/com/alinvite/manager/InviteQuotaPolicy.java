package com.alinvite.manager;

import java.util.Locale;

/**
 * Pure playtime-to-invite-code policy.
 *
 * <p>Two layers live here:
 * <ul>
 *   <li>{@link #eligiblePeriods} keeps the legacy "how many periods did this player complete"
 *       arithmetic used by older call sites.</li>
 *   <li>{@link #boundaryIndex} / {@link #boundaryHours} describe the absolute cumulative-hour
 *       boundaries used by the single-use code ledger (default group: 48h, 96h, 144h ...;
 *       vip1: 24h, 48h, 72h ...).</li>
 * </ul>
 * No Bukkit types are referenced so the rules stay unit-testable.
 */
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

    /**
     * Highest boundary index the player has already reached, i.e. {@code floor(hours / interval)}.
     * Returns 0 for invalid input so callers never see a negative index.
     */
    public static int boundaryIndex(double playtimeHours, double hoursPerSlot) {
        if (!Double.isFinite(playtimeHours) || playtimeHours < 0
                || !Double.isFinite(hoursPerSlot) || hoursPerSlot <= 0) {
            return 0;
        }
        long completed = (long) Math.floor(playtimeHours / hoursPerSlot + 1.0e-9);
        if (completed <= 0) {
            return 0;
        }
        return completed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) completed;
    }

    /** Absolute cumulative hours required to reach boundary {@code index} (1-based). */
    public static double boundaryHours(int index, double hoursPerSlot) {
        if (index <= 0 || !Double.isFinite(hoursPerSlot) || hoursPerSlot <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return index * hoursPerSlot;
    }

    /**
     * First 1-based boundary strictly after the supplied cumulative-time watermark. Policy switches
     * pass the previous rule's last settled boundary, allowing newly eligible faster-rule nodes
     * while preventing a boundary already credited under the previous rule from being replayed.
     */
    public static int firstGrantableBoundary(double playtimeHours, double hoursPerSlot) {
        int current = boundaryIndex(playtimeHours, hoursPerSlot);
        if (current >= Integer.MAX_VALUE - 1) {
            return Integer.MAX_VALUE;
        }
        return current + 1;
    }

    /** True when {@code playtimeHours} has reached boundary {@code index}. */
    public static boolean reached(int index, double playtimeHours, double hoursPerSlot) {
        if (index <= 0) {
            return false;
        }
        return playtimeHours + 1.0e-9 >= boundaryHours(index, hoursPerSlot);
    }
}
