package dev.moonbridge.api.event;

import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.ServerView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable identity and registration snapshot for one online backend transfer. */
public record TransferContext(
        UUID transferId,
        PlayerIdentity player,
        ServerView source,
        ServerView target,
        long sourceRegistrationGeneration,
        long targetRegistrationGeneration,
        long sourceEpoch,
        long targetEpoch,
        Instant deadline) {
    public TransferContext {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(deadline, "deadline");
        if (sourceRegistrationGeneration < 1 || targetRegistrationGeneration < 1) {
            throw new IllegalArgumentException("registration generations must be positive");
        }
        if (sourceEpoch < 0 || targetEpoch < 0) {
            throw new IllegalArgumentException("backend epochs must be non-negative");
        }
    }
}
