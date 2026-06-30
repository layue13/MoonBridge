package dev.strataproxy.network;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConnectionAdmissionControl {
    private final int maxConnections;
    private final int maxConnectionsPerAddress;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> activeByAddress = new ConcurrentHashMap<>();

    public ConnectionAdmissionControl(int maxConnections, int maxConnectionsPerAddress) {
        if (maxConnections <= 0 || maxConnectionsPerAddress <= 0) {
            throw new IllegalArgumentException("connection limits must be positive");
        }
        this.maxConnections = maxConnections;
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
    }

    public Admission acquire(SocketAddress remoteAddress) {
        var key = key(remoteAddress);
        if (!tryIncrement(activeConnections, maxConnections)) {
            return Admission.rejected(key, "global_limit");
        }
        var addressCounter = activeByAddress.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (!tryIncrement(addressCounter, maxConnectionsPerAddress)) {
            activeConnections.decrementAndGet();
            return Admission.rejected(key, "per_address_limit");
        }
        return Admission.accepted(key);
    }

    public void release(Admission admission) {
        if (admission == null || !admission.accepted()) {
            return;
        }
        activeConnections.updateAndGet(value -> Math.max(0, value - 1));
        var counter = activeByAddress.get(admission.addressKey());
        if (counter != null) {
            var remaining = counter.updateAndGet(value -> Math.max(0, value - 1));
            if (remaining <= 0) {
                activeByAddress.remove(admission.addressKey(), counter);
            }
        }
    }

    public long activeConnections() {
        return activeConnections.get();
    }

    public long activeConnectionsFor(String addressKey) {
        var counter = activeByAddress.get(addressKey);
        return counter == null ? 0 : counter.get();
    }

    private static boolean tryIncrement(AtomicInteger counter, int limit) {
        while (true) {
            var current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static String key(SocketAddress remoteAddress) {
        if (remoteAddress instanceof InetSocketAddress inet) {
            var address = inet.getAddress();
            return address == null ? inet.getHostString() : address.getHostAddress();
        }
        return String.valueOf(remoteAddress);
    }

    public record Admission(boolean accepted, String addressKey, String rejectionReason) {
        static Admission accepted(String addressKey) {
            return new Admission(true, addressKey, "");
        }

        static Admission rejected(String addressKey, String reason) {
            return new Admission(false, addressKey, reason);
        }
    }
}
