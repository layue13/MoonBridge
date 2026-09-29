package dev.moonbridge.api;

import java.util.Objects;
import java.util.UUID;

/** Immutable identity and route snapshot for one backend transfer attempt. */
public record TransferContext(UUID transferId, PlayerIdentity player, String sourceBackend,
                              String targetBackend) {
    public TransferContext {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(player, "player");
        validateName(sourceBackend, "sourceBackend");
        validateName(targetBackend, "targetBackend");
    }

    private static void validateName(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > 128) {
            throw new IllegalArgumentException(name + " must be a nonblank backend name without surrounding whitespace");
        }
    }
}
