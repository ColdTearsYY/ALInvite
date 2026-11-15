package com.alinvite.commands;

/** Authorization shared by command routes; never inferred from requested target names. */
public final class OperationAccess {
    private OperationAccess() {}
    public static boolean allowed(boolean adminRoute, boolean playerSender,
                                  boolean usePermission, boolean adminPermission,
                                  boolean gateLocked, boolean mutation) {
        if (adminRoute) return adminPermission;
        return playerSender && usePermission && (!mutation || !gateLocked);
    }
}
