package dev.moonbridge.api.profile;

import dev.moonbridge.api.PlayerIdentity;
import java.util.concurrent.CompletionStage;

/** Registration point for the proxy's singular required profile handoff coordinator. */
public interface ProfileHandoffs {
    /** Registers the fail-closed coordinator. A second provider is rejected. */
    void register(ProfileHandoffCoordinator coordinator);

    /** Queries the current backend's authority for this exact proxy connection. */
    CompletionStage<ProfileState> queryActive(PlayerIdentity identity);
}
