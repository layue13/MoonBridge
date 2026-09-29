package dev.moonbridge.api.profile;

import java.util.concurrent.CompletionStage;

/** A profile reservation that is ready for the matching backend login attempt. */
public interface PreparedProfile {
    /** Notifies the coordinator that the proxy completed network cutover to the target. */
    CompletionStage<Void> routed();

    /**
     * Cancels this reservation. For transfers, success must mean the source is safe to
     * resume under a valid owner epoch; failure means MoonBridge must keep it isolated.
     */
    CompletionStage<Void> abort();
}
