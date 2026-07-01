package dev.strataproxy.plugin.service;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface PlayerService {
    CompletionStage<PlayerTransfer> transfer(String playerName, String targetServer);

    Optional<PlayerView> find(String playerName);

    Collection<PlayerView> onlinePlayers();
}
