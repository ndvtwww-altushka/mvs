package app.mvs.core.net;

import app.mvs.core.util.Backoff;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class RetryInterceptor implements RequestPipeline.Interceptor {
    private final int maxRetries; private final long baseMs, capMs, floodWaitCapMs;
    private final Backoff.Budget budget; private final ScheduledExecutorService timers;

    public RetryInterceptor(int maxRetries, long baseMs, long capMs, long floodWaitCapMs,
                            Backoff.Budget budget, ScheduledExecutorService timers) {
        this.maxRetries = maxRetries; this.baseMs = baseMs; this.capMs = capMs;
        this.floodWaitCapMs = floodWaitCapMs; this.budget = budget; this.timers = timers;
    }

    @Override
    public CompletableFuture<Object> intercept(RequestPipeline.Request r, RequestPipeline.Chain next) {
        return attempt(r, next, new Backoff(baseMs, capMs));
    }

    private CompletableFuture<Object> attempt(RequestPipeline.Request r, RequestPipeline.Chain next, Backoff backoff) {
        CompletableFuture<Object> out = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Object>> inner = new AtomicReference<>();
        AtomicReference<CompletableFuture<Object>> child = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> retryTask = new AtomicReference<>();
        out.whenComplete((res, err) -> {                 // 4a: отмена вниз по цепочке
            if (out.isCancelled()) {
                CompletableFuture<Object> i = inner.get(); if (i != null) i.cancel(true);
                CompletableFuture<Object> ch = child.get(); if (ch != null) ch.cancel(true);
                ScheduledFuture<?> t = retryTask.get(); if (t != null) t.cancel(false);
            }
        });
        CompletableFuture<Object> cur = next.proceed(r);
        inner.set(cur);
        if (out.isCancelled()) { cur.cancel(true); return out; }
        cur.whenComplete((res, err) -> {
            if (out.isCancelled()) return;
            if (err == null) { out.complete(res); return; }
            Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            if (!retriable(cause) || r.attempt >= maxRetries || !budget.tryAcquire()) { out.completeExceptionally(cause); return; }
            long delay;
            if (cause instanceof RpcException && ((RpcException)cause).code == 420) {
                long wait = ((RpcException)cause).retryAfterMs;
                if (wait > floodWaitCapMs) { out.completeExceptionally(cause); return; } // 4c: не сокращаем FLOOD_WAIT
                delay = Math.max(1, wait);
            } else delay = backoff.nextDelayMs();
            r.attempt++;
            try {
                ScheduledFuture<?> t = timers.schedule(() -> {
                    if (out.isCancelled()) return;
                    CompletableFuture<Object> ch = attempt(r, next, backoff);
                    child.set(ch);
                    if (out.isCancelled()) { ch.cancel(true); return; }
                    ch.whenComplete((r2, e2) -> { if (e2 == null) out.complete(r2); else out.completeExceptionally(e2); });
                }, delay, TimeUnit.MILLISECONDS);
                retryTask.set(t);
                if (out.isCancelled()) t.cancel(false);
            } catch (RejectedExecutionException rej) {   // 4d: планировщик мёртв - не подвешиваем Future
                out.completeExceptionally(cause);
            }
        });
        return out;
    }

    /** 4b: только явно разрешённые транзиентные ошибки. */
    private static boolean retriable(Throwable t) {
        if (t instanceof TimeoutException || t instanceof IOException) return true;
        if (t instanceof RpcException) { int c = ((RpcException)t).code; return c == 420 || (c >= 500 && c < 600); }
        return false;
    }
}
