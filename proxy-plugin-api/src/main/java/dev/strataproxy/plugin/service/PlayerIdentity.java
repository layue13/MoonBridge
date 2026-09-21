package dev.strataproxy.plugin.service;

import java.util.UUID;

/** Stable player UUID together with the unique lifetime of one proxy connection. */
public record PlayerIdentity(UUID uuid, String connectionId) {
    public PlayerIdentity {
        connectionId = connectionId == null ? "" : connectionId;
    }
}
