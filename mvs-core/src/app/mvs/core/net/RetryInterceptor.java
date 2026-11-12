package app.mvs.core.net;

import app.mvs.core.util.Backoff;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Retries only explicitly transient failures and never extends the original deadline. */
public final class RetryInterceptor implements RequestPipeline.Interceptor {
    private final int maxRetries;
    private final long floodWaitCapMs;
    private final Backoff.Budget budget;
    private final ScheduledExecutorService timers;
    private final long baseMs, capMs;

    public RetryInterceptor(int maxRetries, long baseMs, long capMs, long floodWaitCapMs,
                            Backoff.Budget budget, ScheduledExecutorService timers) {
        if (maxRetries < 0 || baseMs < 0 || capMs < 0 || floodWaitCapMs < 0
                || budget == null || timers == null) throw new IllegalArgumentException("invalid retry configuration");
        this.maxRetries = maxRetries;
        this.baseMs = baseMs;
        this.capMs = capMs;
        this.floodWaitCapMs = floodWaitCapMs;
        this.budget = budget;
        this.timers = timers;
    }

    @Override public CompletableFuture<Object> intercept(RequestPipeline.Request request,
                                                           RequestPipeline.Chain next) {
        return attempt(request, next, new Backoff(baseMs, capMs));
    }

    private CompletableFuture<Object> attempt(RequestPipeline.Request request,
                                              RequestPipeline.Chain next, Backoff backoff) {
        CompletableFuture<Object> out = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Object>> inner = new AtomicReference<>();
        AtomicReference<CompletableFuture<Object>> child = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> retryTask = new AtomicReference<>();

        out.whenComplete((ignored, error) -> {
            if (!out.isCancelled()) return;
            CompletableFuture<Object> current = inner.get();
            if (current != null) current.cancel(true);
            CompletableFuture<Object> retry = child.get();
            if (retry != null) retry.cancel(true);
            ScheduledFuture<?> scheduled = retryTask.get();
            if (scheduled != null) scheduled.cancel(false);
        });

        long remaining = request.remainingMillis();
        if (remaining == 0) {
            out.completeExceptionally(new TimeoutException("request deadline expired"));
            return out;
        }

        final CompletableFuture<Object> current;
        try {
            current = next.proceed(request.forAttempt(request.attempt, remaining));
            if (current == null) throw new IllegalStateException("pipeline returned null future");
        } catch (Throwable failure) {
            out.completeExceptionally(failure);
            return out;
        }
        inner.set(current);
        if (out.isCancelled()) { current.cancel(true); return out; }

        current.whenComplete((result, error) -> {
            if (out.isCancelled()) return;
            if (error == null) { out.complete(result); return; }
            Throwable cause = unwrap(error);
            if (!isRetriable(cause) || request.attempt >= maxRetries) {
                out.completeExceptionally(cause);
                return;
            }

            long delay;
            if (cause instanceof RpcException && ((RpcException) cause).code == 420) {
                delay = ((RpcException) cause).retryAfterMs;
                if (delay > floodWaitCapMs) { out.completeExceptionally(cause); return; }
                delay = Math.max(1L, delay);
            } else {
                delay = backoff.nextDelayMs();
            }

            long budgetRemaining = request.remainingMillis();
            if (budgetRemaining == 0 || delay >= budgetRemaining) {
                out.completeExceptionally(new TimeoutException("request deadline expired before retry"));
                return;
            }
            if (!budget.tryAcquire()) { out.completeExceptionally(cause); return; }

            try {
                ScheduledFuture<?> scheduled = timers.schedule(() -> {
                    if (out.isCancelled()) return;
                    CompletableFuture<Object> nextAttempt = attempt(
                            request.forAttempt(request.attempt + 1, request.remainingMillis()), next, backoff);
                    child.set(nextAttempt);
                    if (out.isCancelled()) { nextAttempt.cancel(true); return; }
                    nextAttempt.whenComplete((value, failure) -> {
                        if (failure == null) out.complete(value);
                        else out.completeExceptionally(unwrap(failure));
                    });
                }, delay, TimeUnit.MILLISECONDS);
                retryTask.set(scheduled);
                if (out.isCancelled()) scheduled.cancel(false);
            } catch (RejectedExecutionException rejected) {
                out.completeExceptionally(cause);
            }
        });
        return out;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static boolean isRetriable(Throwable failure) {
        if (failure instanceof TimeoutException || failure instanceof IOException) return true;
        if (failure instanceof RpcException) {
            int code = ((RpcException) failure).code;
            return code == 420 || (code >= 500 && code < 600);
        }
        return false;
    }
}
