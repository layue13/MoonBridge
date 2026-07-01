package dev.strataproxy.plugin.service;

import java.util.Collection;
import java.util.Optional;

public interface ServerService {
    Optional<ServerView> find(String serverName);

    Optional<ServerView> firstWithTag(String tag);

    Collection<ServerView> servers();
}
