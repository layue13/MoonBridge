package dev.moonbridge.core.plugin;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Stage mapping whose cancellation reaches the upstream stage. */
final class CancellableStages {
    private CancellableStages() { }

    static <A, B> CompletableFuture<B> map(
            CompletionStage<A> stage, Function<? super A, ? extends B> mapper) {
        CompletableFuture<A> upstream = stage.toCompletableFuture();
        Object completionLock = new Object();
        CompletableFuture<B> mapped = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (completionLock) {
                    boolean cancelled = super.cancel(mayInterruptIfRunning);
                    if (cancelled) upstream.cancel(mayInterruptIfRunning);
                    return cancelled;
                }
            }
        };
        upstream.whenComplete((value, failure) -> {
            synchronized (completionLock) {
                if (mapped.isDone()) return;
                if (failure != null) mapped.completeExceptionally(failure);
                else {
                    try {
                        mapped.complete(mapper.apply(value));
                    } catch (Throwable mappingFailure) {
                        mapped.completeExceptionally(mappingFailure);
                    }
                }
            }
        });
        return mapped;
    }
}
