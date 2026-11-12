package app.mvs.core.net;

import app.mvs.core.util.Clock;

public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }
    private final Clock clock; private final int window, minCalls, failPct;
    private final long cooldownBase, cooldownMax, probeTimeoutMs;
    private final boolean[] ring; private int pos, count, fails;
    private State state = State.CLOSED; private long openUntil, cooldown;
    private long nextProbeToken = 1, activeProbe, probeDeadline;

    public CircuitBreaker(Clock clock, int window, int minCalls, int failPct, long cooldownBaseMs, long cooldownMaxMs) {
        this(clock, window, minCalls, failPct, cooldownBaseMs, cooldownMaxMs, 30000);
    }

    public CircuitBreaker(Clock clock, int window, int minCalls, int failPct, long cooldownBaseMs, long cooldownMaxMs, long probeTimeoutMs) {
        this.clock = clock; this.window = window; this.minCalls = minCalls; this.failPct = failPct;
        this.cooldownBase = cooldownBaseMs; this.cooldownMax = cooldownMaxMs; this.probeTimeoutMs = probeTimeoutMs;
        this.cooldown = cooldownBaseMs; this.ring = new boolean[window];
    }

    public synchronized boolean peek() { return state != State.OPEN || clock.nowMs() >= openUntil; }

    /** -1 отказ; 0 обычный запрос (CLOSED); >0 токен пробы (HALF_OPEN, ровно одна). */
    public synchronized long allow() {
        long now = clock.nowMs();
        switch (state) {
            case CLOSED: return 0;
            case OPEN:
                if (now >= openUntil) {
                    state = State.HALF_OPEN; activeProbe = nextProbeToken++; probeDeadline = now + probeTimeoutMs;
                    return activeProbe;
                }
                return -1;
            case HALF_OPEN:
                if (activeProbe != 0 && now >= probeDeadline) { open(); return -1; }  // проба залипла
                return -1;                                                            // параллельные пробы запрещены
            default: return -1;
        }
    }

    public synchronized void record(boolean ok) { record(ok, 0); }

    public synchronized void record(boolean ok, long token) {
        if (token > 0) {
            if (state != State.HALF_OPEN || token != activeProbe) return;   // не настоящая проба - игнор
            activeProbe = 0;
            if (ok) { state = State.CLOSED; cooldown = cooldownBase; clear(); } else open();
            return;
        }
        if (state != CLOSED) return;   // обычный запрос не трогает OPEN/HALF_OPEN
        if (count == window) { if (!ring[pos]) fails--; } else count++;
        ring[pos] = ok; if (!ok) fails++;
        pos = (pos + 1) % window;
        if (count >= minCalls && fails * 100 / count >= failPct) open();
    }

    public synchronized State state() { return state; }

    private void open() {
        state = State.OPEN; openUntil = clock.nowMs() + cooldown;
        cooldown = Math.min(cooldownMax, cooldown * 2); activeProbe = 0; clear();
    }

    private void clear() { pos = 0; count = 0; fails = 0; }
}
