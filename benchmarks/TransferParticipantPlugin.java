package dev.moonbridge.core.session;

import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.event.TransferDecision;
import dev.moonbridge.api.event.TransferPreparingEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Loaded reflectively so the zero-subscriber harness also runs against pre-coordination baseline jars. */
public final class TransferParticipantPlugin implements Plugin {
    private final int count;
    private final boolean releaseSource;
    private final int delayMillis;

    public TransferParticipantPlugin(int count, boolean releaseSource, int delayMillis) {
        this.count = count;
        this.releaseSource = releaseSource;
        this.delayMillis = delayMillis;
    }

    @Override public void onLoad(PluginContext context) {
        for (int i = 0; i < count; i++) {
            context.events().subscribe(TransferPreparingEvent.class, event -> {
                CompletableFuture<TransferDecision> result = new CompletableFuture<>();
                completeAfter(delayMillis, () -> result.complete(releaseSource
                        ? TransferDecision.releaseSource(ignored -> delayedVoid(delayMillis))
                        : TransferDecision.allow()));
                return result;
            });
        }
    }

    private static CompletableFuture<Void> delayedVoid(int delayMillis) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        completeAfter(delayMillis, () -> result.complete(null));
        return result;
    }

    private static void completeAfter(int delayMillis, Runnable task) {
        if (delayMillis == 0) task.run();
        else CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(task);
    }
}
