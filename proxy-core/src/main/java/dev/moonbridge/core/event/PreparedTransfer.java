package dev.moonbridge.core.event;

import dev.moonbridge.api.event.SourceReleasedEvent;
import java.util.concurrent.CompletionStage;

/** Immutable result of transfer preparation, retained by the core for the selected transfer. */
public interface PreparedTransfer {
    /** Whether one or more selected listeners require the source-release phase. */
    boolean requiresSourceRelease();

    /** Runs required callbacks in registration order after the exact source socket closes. */
    CompletionStage<Void> sourceReleased(SourceReleasedEvent event);
}
