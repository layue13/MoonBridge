package dev.moonbridge.api.permission;

import dev.moonbridge.api.PlayerView;
import java.util.concurrent.CompletionStage;

/** Opens permission state for an authenticated player session. Loading must not block a proxy event loop. */
@FunctionalInterface
public interface PermissionProvider {
    CompletionStage<PermissionSubject> open(PlayerView player);
}
