package dev.strataproxy.api.server;

public interface RegisteredServer {
    ServerDescriptor descriptor();

    ServerHealth health();

    ServerLoad load();

    boolean draining();
}
