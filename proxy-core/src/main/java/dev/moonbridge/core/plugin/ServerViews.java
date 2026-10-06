package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.ServerView;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.backend.BackendView;

import java.util.List;

/** Plugin-facing snapshots of catalog entries. */
final class ServerViews {
    private ServerViews() { }

    static ServerView of(BackendView view) {
        return new ServerView(view.handle().id().value(), view.address(), view.tags(), view.metadata());
    }

    static List<ServerView> snapshot(BackendCatalog catalog) {
        return catalog.snapshot().stream().map(ServerViews::of).toList();
    }
}
