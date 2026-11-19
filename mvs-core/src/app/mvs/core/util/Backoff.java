package app.mvs.core.util;

import java.util.concurrent.ThreadLocalRandom;

/** Exponential full-jitter backoff and a process-local retry token bucket. */
public final class Backoff {
    private final long baseMs;
    private final long capMs;
    private int attempt;

    public Backoff(long baseMs, long capMs) {
        if (baseMs < 0 || capMs < 0) throw new IllegalArgumentException("backoff values must be >= 0");
        this.baseMs = baseMs;
        this.capMs = capMs;
    }

    public synchronized long nextDelayMs() {
        int exponent = Math.min(attempt++, 62);
        long bound = Math.min(baseMs, capMs);
        for (int i = 0; i < exponent && bound < capMs; i++) {
            bound = bound > capMs / 2 ? capMs : bound * 2;
        }
        if (bound <= 0) return 0;
        // nextLong(bound + 1) overflows when bound == Long.MAX_VALUE.
        return bound == Long.MAX_VALUE
                ? ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)
                : ThreadLocalRandom.current().nextLong(bound + 1);
    }

    public synchronized void reset() { attempt = 0; }

    public static final class Budget {
        private final double capacity;
        private final double refillPerSec;
        private final Clock clock;
        private double tokens;
        private long last;

        public Budget(double capacity, double refillPerSec, Clock clock) {
            if (!Double.isFinite(capacity) || capacity < 0
                    || !Double.isFinite(refillPerSec) || refillPerSec < 0 || clock == null) {
                throw new IllegalArgumentException("invalid retry budget");
            }
            this.capacity = capacity;
            this.refillPerSec = refillPerSec;
            this.clock = clock;
            this.tokens = capacity;
            this.last = clock.nowMs();
        }

        public synchronized boolean tryAcquire() {
            long now = clock.nowMs();
            // A clock moving backwards must never mint tokens. Avoid subtraction overflow.
            long elapsed = now > last ? now - last : 0;
            if (now > last) last = now;
            double refill = (elapsed / 1000.0) * refillPerSec;
            tokens = Math.min(capacity, tokens + refill);
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }

        public synchronized double availableTokens() { return tokens; }
    }
}
