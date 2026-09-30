package dev.moonbridge.api.event;

import java.util.concurrent.CompletionStage;

/** Confirms a participant's durable boundary after the source backend socket has closed. */
@FunctionalInterface
public interface SourceReleasedHandler {
    /** Successful completion confirms the participant's durable boundary. */
    CompletionStage<Void> onSourceReleased(SourceReleasedEvent event);
}
