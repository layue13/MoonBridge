package dev.moonbridge.core.control;

import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.backend.BackendHandle;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;

import java.io.IOException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Backend leases: who is registered, on which connection, until when. All state is guarded by one lock. */
final class LeaseRegistry {
    private final ProxyConfiguration.BackendChannel configuration;
    private final BackendCatalog catalog;
    private final BooleanSupplier closed;
    private final Object lock = new Object();
    private final Map<String, Lease> byInstance = new HashMap<>();
    private final Map<String, Lease> byBackend = new HashMap<>();
    private final AtomicLong nextEpoch = new AtomicLong();

    LeaseRegistry(ProxyConfiguration.BackendChannel configuration, BackendCatalog catalog, BooleanSupplier closed) {
        this.configuration = configuration;
        this.catalog = catalog;
        this.closed = closed;
    }

    void register(Connection connection, RegistrationVerifier.Registration requested) throws IOException {
        synchronized (lock) {
            if (closed.getAsBoolean()) throw new IOException("control service is closed");
            Lease conflict = byBackend.get(requested.name());
            if (conflict != null && !conflict.instanceId.equals(requested.instanceId()))
                throw new IOException("backend name already registered");
            Lease prior = byInstance.get(requested.instanceId());
            if (prior != null && !prior.name.equals(requested.name()))
                throw new IOException("instance cannot change backend name");
            long epoch = nextEpoch.incrementAndGet();
            BackendOwner owner = new BackendOwner("backend-control:" + requested.instanceId(), epoch);
            BackendRegistration registration = new BackendRegistration(new BackendId(requested.name()), owner,
                    requested.address(), Map.of("discovery", "control"),
                    Map.of("instance.id", requested.instanceId(), "instance.generation", requested.generation()));
            BackendHandle handle;
            try { handle = catalog.register(registration).handle(); }
            catch (RuntimeException conflictFailure) { throw new IOException("backend registration rejected", conflictFailure); }
            connection.instanceId = requested.instanceId();
            connection.name = requested.name();
            connection.epoch = epoch;
            connection.allowedNamespaces = requested.allowedNamespaces();
            connection.allowedReceiveNamespaces = requested.allowedReceiveNamespaces();
            Lease replacement = new Lease(requested.instanceId(), requested.name(), requested.generation(), epoch, handle,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.leaseSeconds()), connection);
            byInstance.put(requested.instanceId(), replacement);
            byBackend.put(requested.name(), replacement);
            if (prior != null && prior.connection != null) prior.connection.close();
        }
    }

    boolean isCurrent(Connection connection) {
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            return lease != null && lease.connection == connection && lease.epoch() == connection.epoch;
        }
    }

    void renew(Connection connection) {
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            if (lease != null && lease.connection == connection)
                lease.expiresAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(configuration.leaseSeconds());
        }
    }

    void unregister(Connection connection) {
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            if (lease != null && lease.connection == connection) removeLease(lease);
        }
    }

    void disconnect(Connection connection) {
        connection.close();
        synchronized (lock) {
            Lease lease = byInstance.get(connection.instanceId);
            if (lease != null && lease.connection == connection) lease.connection = null;
        }
    }

    void expireLeases() {
        synchronized (lock) {
            long now = System.nanoTime();
            for (Lease lease : java.util.List.copyOf(byInstance.values())) {
                if (lease.expiresAtNanos <= now) removeLease(lease);
            }
        }
    }

    private void removeLease(Lease lease) {
        if (byInstance.get(lease.instanceId) != lease) return;
        byInstance.remove(lease.instanceId);
        byBackend.remove(lease.name, lease);
        catalog.remove(lease.handle);
        if (lease.connection != null) lease.connection.close();
    }

    Connection current(String name) {
        synchronized (lock) {
            Lease lease = byBackend.get(name);
            return lease == null || lease.connection == null || !lease.connection.live.get()
                    ? null : lease.connection;
        }
    }

    List<Connection> connectedBackends() {
        synchronized (lock) {
            return byBackend.values().stream().map(lease -> lease.connection)
                    .filter(Objects::nonNull).filter(connection -> connection.live.get())
                    .sorted(java.util.Comparator.comparing(connection -> connection.name)).toList();
        }
    }

    void closeAll() {
        synchronized (lock) {
            for (Lease lease : java.util.List.copyOf(byInstance.values())) removeLease(lease);
        }
    }

    static final class Lease {
        final String instanceId, name, generation;
        final long epoch;
        final BackendHandle handle;
        long expiresAtNanos;
        Connection connection;
        Lease(String instanceId, String name, String generation, long epoch, BackendHandle handle,
                      long expiresAtNanos, Connection connection) {
            this.instanceId = instanceId;
            this.name = name;
            this.generation = generation;
            this.epoch = epoch;
            this.handle = handle;
            this.expiresAtNanos = expiresAtNanos;
            this.connection = connection;
        }
        long epoch() { return epoch; }
    }
}
