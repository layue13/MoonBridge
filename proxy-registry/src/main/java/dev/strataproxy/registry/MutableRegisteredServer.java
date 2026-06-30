package dev.strataproxy.registry;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class MutableRegisteredServer implements RegisteredServer {
    private final AtomicReference<ServerDescriptor> descriptor;
    private final AtomicReference<ServerHealth> health;
    private final AtomicReference<ServerLoad> load;
    private final AtomicBoolean draining;

    MutableRegisteredServer(ServerDescriptor descriptor) {
        this.descriptor = new AtomicReference<>(Objects.requireNonNull(descriptor, "descriptor"));
        this.health = new AtomicReference<>(ServerHealth.up(-1));
        this.load = new AtomicReference<>(new ServerLoad(0, descriptor.softCapacity(), descriptor.hardCapacity(), 0, 0, 0, 0.0d));
        this.draining = new AtomicBoolean(descriptor.drainMode());
    }

    @Override
    public ServerDescriptor descriptor() {
        var descriptor = this.descriptor.get();
        var drainMode = draining.get();
        if (descriptor.drainMode() == drainMode) {
            return descriptor;
        }
        return new ServerDescriptor(
                descriptor.name(),
                descriptor.address(),
                descriptor.tags(),
                descriptor.capabilities(),
                descriptor.protocolRange(),
                descriptor.weight(),
                descriptor.softCapacity(),
                descriptor.hardCapacity(),
                drainMode,
                descriptor.metadata());
    }

    @Override
    public ServerHealth health() {
        return health.get();
    }

    @Override
    public ServerLoad load() {
        return load.get();
    }

    @Override
    public boolean draining() {
        return draining.get();
    }

    void updateHealth(ServerHealth next) {
        health.set(Objects.requireNonNull(next, "next"));
    }

    void updateLoad(ServerLoad next) {
        load.set(Objects.requireNonNull(next, "next"));
    }

    void beginDrain() {
        draining.set(true);
    }

    void updateDrainMode(boolean drainMode) {
        draining.set(drainMode);
    }

    void replaceDescriptor(ServerDescriptor next) {
        var descriptor = Objects.requireNonNull(next, "next");
        this.descriptor.set(descriptor);
        draining.set(descriptor.drainMode());
        load.updateAndGet(current -> new ServerLoad(
                current.players(),
                descriptor.softCapacity(),
                descriptor.hardCapacity(),
                current.inboundBytesPerSecond(),
                current.outboundBytesPerSecond(),
                current.packetsPerSecond(),
                current.eventLoopDelayMillis()));
    }
}
