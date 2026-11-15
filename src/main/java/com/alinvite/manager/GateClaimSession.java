package com.alinvite.manager;

/** One login's admission window. Only a claim accepted before the deadline delays timeout. */
public final class GateClaimSession {
    private final long deadlineMillis;
    private boolean initializing = true;
    private boolean closed;
    private boolean claiming;
    private long sequence;
    private long active;

    public GateClaimSession(long deadlineMillis) { this.deadlineMillis = deadlineMillis; }

    public synchronized void ready() { initializing = false; }

    public synchronized boolean accepts(long now) { return !closed && now < deadlineMillis; }

    /** Zero means another lookup is active, initialization is incomplete, or the window is closed. */
    public synchronized long beginLookup(long now) { return begin(now, false); }

    /** Join admission may inspect a pre-invite before hidden registration is considered. */
    public synchronized long beginInitialLookup(long now) { return begin(now, true); }

    private long begin(long now, boolean initial) {
        if ((!initial && initializing) || active != 0 || !accepts(now)) return 0;
        active = ++sequence;
        return active;
    }

    /** Called on the IO thread immediately before the guarded transaction consumes its code. */
    public synchronized boolean claim(long token, long now) {
        if (closed || token == 0 || active != token) return false;
        if (!claiming && now >= deadlineMillis) return false;
        claiming = true;
        return true;
    }

    public synchronized void finish(long token) {
        if (token == active) { active = 0; claiming = false; }
    }

    /** A lookup alone never postpones the deadline; a transaction already accepted may finish. */
    public synchronized boolean expire(long now) {
        if (closed || now < deadlineMillis || claiming) return false;
        close();
        return true;
    }

    public synchronized void close() { closed = true; active = 0; claiming = false; }
}
