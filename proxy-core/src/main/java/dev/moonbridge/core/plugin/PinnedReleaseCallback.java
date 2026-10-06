package dev.moonbridge.core.plugin;

import dev.moonbridge.api.event.TransferDecision;
import dev.moonbridge.api.event.TransferPreparingEvent;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

    final class PinnedReleaseCallback {
        private final EventRegistration<TransferPreparingEvent, TransferDecision> registration;
        private final java.util.function.Supplier<CompletionStage<Void>> afterSourceClosed;

        PinnedReleaseCallback(EventRegistration<TransferPreparingEvent, TransferDecision> registration,
                                      java.util.function.Supplier<CompletionStage<Void>> afterSourceClosed) {
            this.registration = registration;
            this.afterSourceClosed = afterSourceClosed;
        }

        CompletionStage<Void> invoke() {
            registration.requireActive();
            CompletionStage<Void> stage = Objects.requireNonNull(
                    afterSourceClosed.get(), "source release callback stage");
            return CancellableStages.map(stage, ignored -> {
                requireActive();
                return null;
            });
        }

        void requireActive() {
            registration.requireActive();
        }
    }
