package app.mvs.core.net;

import app.mvs.core.util.Clock;
import java.util.HashMap;
import java.util.Map;

public final class DcSupervisor {
    private final Clock clock;
    private final Map<Integer, CircuitBreaker> breakers = new HashMap<>();
    private final Map<Integer, Double> ewma = new HashMap<>();
    private volatile boolean offline;

    public DcSupervisor(Clock clock) { this.clock = clock; }

    public CircuitBreaker breaker(int dc) {
        synchronized (breakers) {
            return breakers.computeIfAbsent(dc, k -> new CircuitBreaker(clock, 32, 8, 50, 500, 30000));
        }
    }

    /** Advisory: пока нативная маршрутизация не реализована, фактический DC выбирает движок. */
    public int select(int preferred, int[] candidates) {
        if (offline) return -1;
        if (breaker(preferred).peek()) return preferred;
        int best = -1; double bestScore = Double.MAX_VALUE;
        for (int dc : candidates) {
            if (!breaker(dc).peek()) continue;
            double s; synchronized (ewma) { s = ewma.getOrDefault(dc, 200.0); }
            if (s < bestScore) { bestScore = s; best = dc; }
        }
        return best;
    }

    public void onResult(int dc, boolean ok, long rttMs) { onResult(dc, ok, rttMs, 0); }

    public void onResult(int dc, boolean ok, long rttMs, long probeToken) {
        breaker(dc).record(ok, probeToken);
        if (ok) synchronized (ewma) { ewma.merge(dc, (double)rttMs, (o, n) -> o * 0.8 + n * 0.2); }
    }

    public void setOffline(boolean v) { offline = v; }
    public boolean isOffline() { return offline; }
}
