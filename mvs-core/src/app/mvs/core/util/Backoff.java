package app.mvs.core.util;

import java.util.concurrent.ThreadLocalRandom;

public final class Backoff {
    private final long baseMs, capMs; private int attempt;

    public Backoff(long baseMs, long capMs) {
        this.baseMs = Math.max(0, baseMs); this.capMs = Math.max(0, capMs);
    }

    public long nextDelayMs() {
        int a = Math.min(attempt++, 30);
        long exp = baseMs;
        for (int i = 0; i < a && exp < capMs; i++) exp = exp > capMs / 2 ? capMs : exp * 2;
        if (exp > capMs) exp = capMs;
        if (exp <= 0) return 0;
        long bound = exp == Long.MAX_VALUE ? Long.MAX_VALUE : exp + 1;
        return ThreadLocalRandom.current().nextLong(bound);
    }

    public void reset() { attempt = 0; }

    public static final class Budget {
        private final double capacity, refillPerSec; private final Clock clock;
        private double tokens; private long last;

        public Budget(double capacity, double refillPerSec, Clock clock) {
            this.capacity = capacity; this.refillPerSec = refillPerSec; this.clock = clock;
            this.tokens = capacity; this.last = clock.nowMs();
        }

        public synchronized boolean tryAcquire() {
            long now = clock.nowMs();
            long elapsed = Math.max(0, now - last);
            tokens = Math.min(capacity, tokens + elapsed / 1000.0 * refillPerSec);
            last = now;
            if (tokens >= 1.0) { tokens -= 1.0; return true; }
            return false;
        }
    }
}
