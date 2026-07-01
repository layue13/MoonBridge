package dev.strataproxy.plugin.service;

import java.net.InetSocketAddress;
import java.util.Set;

public record ServerView(
        String name,
        InetSocketAddress address,
        Set<String> tags,
        boolean drainMode,
        int softCapacity,
        int hardCapacity) {
    public ServerView {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
