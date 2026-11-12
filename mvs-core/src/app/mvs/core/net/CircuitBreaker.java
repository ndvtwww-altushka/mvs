package app.mvs.core.net;

import app.mvs.core.util.Clock;

/** Sliding-window circuit breaker with a single, time-bounded half-open probe. */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final Clock clock;
    private final int window, minCalls, failPct;
    private final long cooldownBase, cooldownMax, probeTimeoutMs;
    private final boolean[] ring;
    private int pos, count, fails;
    private State state = State.CLOSED;
    private long openUntil, cooldown;
    private long nextProbeToken = 1, activeProbe, probeDeadline;

    public CircuitBreaker(Clock clock, int window, int minCalls, int failPct,
                          long cooldownBaseMs, long cooldownMaxMs) {
        this(clock, window, minCalls, failPct, cooldownBaseMs, cooldownMaxMs, 30_000);
    }

    public CircuitBreaker(Clock clock, int window, int minCalls, int failPct,
                          long cooldownBaseMs, long cooldownMaxMs, long probeTimeoutMs) {
        if (clock == null) throw new IllegalArgumentException("clock == null");
        if (window < 1 || minCalls < 1 || minCalls > window) throw new IllegalArgumentException("invalid window");
        if (failPct < 1 || failPct > 100) throw new IllegalArgumentException("failPct must be 1..100");
        if (cooldownBaseMs < 1 || cooldownMaxMs < cooldownBaseMs || probeTimeoutMs < 1)
            throw new IllegalArgumentException("invalid breaker durations");
        this.clock = clock;
        this.window = window;
        this.minCalls = minCalls;
        this.failPct = failPct;
        this.cooldownBase = cooldownBaseMs;
        this.cooldownMax = cooldownMaxMs;
        this.probeTimeoutMs = probeTimeoutMs;
        this.cooldown = cooldownBaseMs;
        this.ring = new boolean[window];
    }

    /** True only when an ordinary call or a fresh probe could currently be admitted. */
    public synchronized boolean peek() {
        if (state == State.CLOSED) return true;
        return state == State.OPEN && clock.nowMs() >= openUntil;
    }

    /** -1 = reject, 0 = ordinary closed-state call, positive = exclusive probe token. */
    public synchronized long allow() {
        long now = clock.nowMs();
        switch (state) {
            case CLOSED:
                return 0;
            case OPEN:
                if (now >= openUntil) {
                    state = State.HALF_OPEN;
                    activeProbe = nextToken();
                    probeDeadline = saturatingAdd(now, probeTimeoutMs);
                    return activeProbe;
                }
                return -1;
            case HALF_OPEN:
                if (activeProbe != 0 && now >= probeDeadline) open();
                return -1;
            default:
                return -1;
        }
    }

    public synchronized void record(boolean ok) { record(ok, 0); }

    public synchronized void record(boolean ok, long token) {
        if (token > 0) {
            if (state != State.HALF_OPEN || token != activeProbe) return;
            activeProbe = 0;
            if (ok) {
                state = State.CLOSED;
                cooldown = cooldownBase;
                clearWindow();
            } else {
                open();
            }
            return;
        }
        if (state != State.CLOSED) return;
        if (count == window) {
            if (!ring[pos]) fails--;
        } else {
            count++;
        }
        ring[pos] = ok;
        if (!ok) fails++;
        pos = (pos + 1) % window;
        if (count >= minCalls && ((long) fails * 100L) / count >= failPct) open();
    }

    /** Release a half-open probe cancelled by the caller without counting it as a failure. */
    public synchronized void abandonProbe(long token) {
        if (token <= 0 || state != State.HALF_OPEN || token != activeProbe) return;
        activeProbe = 0;
        state = State.OPEN;
        openUntil = saturatingAdd(clock.nowMs(), cooldown);
    }

    public synchronized State state() { return state; }

    private long nextToken() {
        long token = nextProbeToken++;
        if (token <= 0) { nextProbeToken = 2; token = 1; }
        return token;
    }

    private void open() {
        state = State.OPEN;
        openUntil = saturatingAdd(clock.nowMs(), cooldown);
        cooldown = cooldown > cooldownMax / 2 ? cooldownMax : Math.min(cooldownMax, cooldown * 2);
        activeProbe = 0;
        clearWindow();
    }

    private void clearWindow() { pos = 0; count = 0; fails = 0; }

    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        if (b < 0 && a < Long.MIN_VALUE - b) return Long.MIN_VALUE;
        return a + b;
    }
}
