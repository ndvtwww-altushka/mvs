package app.mvs.core.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * ВНИМАНИЕ: этого файла НЕТ в во.txt (часть 1). Класс только используется
 * (MvsNetCore, RetryInterceptor, тесты), поэтому восстановлен по его вызовам.
 * Это реконструкция, а не исходник автора аудита.
 */
public final class RequestPipeline {
    public static final class Request {
        public final String method; public final Object tl; public final CallScheduler.Options opts;
        public int attempt;
        public Request(String method, Object tl, CallScheduler.Options opts) {
            this.method = method; this.tl = tl; this.opts = opts;
        }
    }

    public interface Interceptor {
        CompletableFuture<Object> intercept(Request r, Chain next);
    }

    public interface Chain {
        CompletableFuture<Object> proceed(Request r);
    }

    private final List<Interceptor> interceptors;
    private final Function<Request, CompletableFuture<Object>> terminal;

    public RequestPipeline(List<Interceptor> interceptors, Function<Request, CompletableFuture<Object>> terminal) {
        this.interceptors = Collections.unmodifiableList(new ArrayList<>(interceptors));
        this.terminal = terminal;
    }

    public CompletableFuture<Object> execute(Request r) { return chain(0).proceed(r); }

    private Chain chain(int i) {
        if (i >= interceptors.size()) return terminal::apply;
        return r -> interceptors.get(i).intercept(r, chain(i + 1));
    }
}
