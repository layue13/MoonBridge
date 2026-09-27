package dev.moonbridge.core.auth;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Injectable, asynchronous check of a Minecraft client login session. */
@FunctionalInterface
public interface SessionVerifier {
    /**
     * A missing profile means the credentials did not match. Implementations must keep blocking
     * network work off the caller's event loop.
     *
     * @param username name from Login Start
     * @param serverHash signed hexadecimal server hash
     * @param clientIp optional connecting client IP, or {@code null} to omit it
     */
    CompletionStage<Optional<VerifiedProfile>> verify(String username, String serverHash, String clientIp);
}
