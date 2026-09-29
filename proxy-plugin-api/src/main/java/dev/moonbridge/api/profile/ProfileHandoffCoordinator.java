package dev.moonbridge.api.profile;

import java.util.concurrent.CompletionStage;

/**
 * Required profile preparation SPI. Registering a coordinator enables fail-closed
 * profile routing: any exception, timeout, null result or stale request denies routing.
 */
public interface ProfileHandoffCoordinator {
    /** Prepares a fixed profile revision/reservation before initial backend login bytes are sent. */
    CompletionStage<PreparedProfile> prepareAdmission(ProfileHandoffRequest request);

    /**
     * Freezes and snapshots the source before a transfer candidate is dialed. The
     * implementation must obtain a source-server barrier before final capture.
     */
    CompletionStage<PreparedProfile> prepareTransfer(ProfileHandoffRequest request,
                                                      SourceInputBarrier proxyBarrier);

    /** Reads authority state for the exact currently routed connection and backend registration. */
    CompletionStage<ProfileState> queryActive(ProfileSession session);
}
