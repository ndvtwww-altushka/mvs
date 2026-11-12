package app.mvs.core.net;

import app.mvs.core.util.Clock;
import java.util.HashMap;
import java.util.Map;

/** Health estimates and circuit breakers keyed by logical/native data-centre id. */
public final class DcSupervisor {
    private final Clock clock;
    private final Map<Integer, CircuitBreaker> breakers = new HashMap<>();
    private final Map<Integer, Double> ewma = new HashMap<>();
    private volatile boolean offline;

    public DcSupervisor(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock == null");
        this.clock = clock;
    }

    public CircuitBreaker breaker(int dc) {
        synchronized (breakers) {
            return breakers.computeIfAbsent(dc,
                    ignored -> new CircuitBreaker(clock, 32, 8, 50, 500, 30_000));
        }
    }

    /** Advisory choice only. It is authoritative only when the native adapter accepts DC routing. */
    public int select(int preferred, int[] candidates) {
        if (offline) return -1;
        if (preferred > 0 && breaker(preferred).peek()) return preferred;
        if (candidates == null) return -1;
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int dc : candidates) {
            if (dc <= 0 || !breaker(dc).peek()) continue;
            double score;
            synchronized (ewma) { score = ewma.getOrDefault(dc, 200.0); }
            if (score < bestScore) { bestScore = score; best = dc; }
        }
        return best;
    }

    public void onResult(int dc, boolean ok, long rttMs) { onResult(dc, ok, rttMs, 0); }

    public void onResult(int dc, boolean ok, long rttMs, long probeToken) {
        breaker(dc).record(ok, probeToken);
        if (ok) {
            double sample = Math.max(0L, rttMs);
            synchronized (ewma) {
                ewma.merge(dc, sample, (oldValue, newValue) -> oldValue * 0.8 + newValue * 0.2);
            }
        }
    }

    public void onCancelled(int dc, long probeToken) {
        breaker(dc).abandonProbe(probeToken);
    }

    public void setOffline(boolean value) { offline = value; }
    public boolean isOffline() { return offline; }
}
