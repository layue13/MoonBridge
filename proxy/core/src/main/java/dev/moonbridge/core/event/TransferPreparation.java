package dev.moonbridge.core.event;

import dev.moonbridge.api.event.TransferPreparingEvent;
import java.util.concurrent.CompletionStage;

/** Host-selected, owner-pinned transfer preparation cohort for one transfer. */
public interface TransferPreparation {
    CompletionStage<PreparedTransfer> prepare(TransferPreparingEvent event);
}
