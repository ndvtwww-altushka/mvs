package app.mvs.core.util;

public interface Clock {
    long nowMs();
    Clock SYSTEM = new Clock() { public long nowMs() { return System.currentTimeMillis(); } };
    Clock MONOTONIC = new Clock() { public long nowMs() { return System.nanoTime() / 1_000_000L; } };
}
