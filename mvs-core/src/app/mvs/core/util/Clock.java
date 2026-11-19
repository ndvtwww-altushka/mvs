package app.mvs.core.util;

/** A clock abstraction. Use MONOTONIC for durations and deadlines. */
public interface Clock {
    long nowMs();

    /** Wall-clock time; retained for callers that need calendar timestamps. */
    Clock SYSTEM = new Clock() {
        @Override public long nowMs() { return System.currentTimeMillis(); }
    };

    /** Monotonic time; safe for timeouts, TTLs and circuit-breaker windows. */
    Clock MONOTONIC = new Clock() {
        @Override public long nowMs() { return System.nanoTime() / 1_000_000L; }
    };
}
