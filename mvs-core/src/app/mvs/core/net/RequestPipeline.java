package app.mvs.core.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Immutable interceptor chain with one absolute end-to-end request deadline. */
public final class RequestPipeline {
    public static final class Request {
        public final String method;
        public final Object tl;
        public final CallScheduler.Options opts;
        public final int attempt;
        public final long deadlineNanos;

        public Request(String method, Object tl, CallScheduler.Options opts) {
            this(method, tl, requireOptions(opts).copy(), 0,
                    deadlineFromNow(opts.timeoutMs));
        }

        private Request(String method, Object tl, CallScheduler.Options opts,
                        int attempt, long deadlineNanos) {
            if (method == null || method.trim().isEmpty()) throw new IllegalArgumentException("method is empty");
            this.method = method;
            this.tl = tl;
            this.opts = opts;
            this.attempt = attempt;
            this.deadlineNanos = deadlineNanos;
        }

        Request forAttempt(int attemptNumber, long timeoutMs) {
            CallScheduler.Options copy = opts.copy();
            copy.timeoutMs = Math.max(1L, timeoutMs);
            return new Request(method, tl, copy, attemptNumber, deadlineNanos);
        }

        public long remainingMillis() {
            long now = System.nanoTime();
            if (deadlineNanos == Long.MAX_VALUE && now < 0) return Long.MAX_VALUE;
            long remaining = deadlineNanos - now;
            if (remaining <= 0) return 0;
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining - 1) + 1L);
        }

        private static CallScheduler.Options requireOptions(CallScheduler.Options options) {
            if (options == null) throw new IllegalArgumentException("options == null");
            if (options.timeoutMs < 1) throw new IllegalArgumentException("timeoutMs must be > 0");
            return options;
        }

        private static long deadlineFromNow(long timeoutMs) {
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            long now = System.nanoTime();
            return now > 0 && nanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
        }
    }

    public interface Interceptor {
        CompletableFuture<Object> intercept(Request request, Chain next);
    }

    public interface Chain { CompletableFuture<Object> proceed(Request request); }

    private final List<Interceptor> interceptors;
    private final Function<Request, CompletableFuture<Object>> terminal;

    public RequestPipeline(List<Interceptor> interceptors,
                           Function<Request, CompletableFuture<Object>> terminal) {
        if (interceptors == null || terminal == null) throw new IllegalArgumentException("pipeline arguments == null");
        this.interceptors = Collections.unmodifiableList(new ArrayList<>(interceptors));
        this.terminal = terminal;
    }

    public CompletableFuture<Object> execute(Request request) {
        if (request == null) throw new IllegalArgumentException("request == null");
        return chain(0).proceed(request);
    }

    private Chain chain(int index) {
        if (index >= interceptors.size()) return terminal::apply;
        Interceptor interceptor = interceptors.get(index);
        Chain next = chain(index + 1);
        return request -> interceptor.intercept(request, next);
    }
}
