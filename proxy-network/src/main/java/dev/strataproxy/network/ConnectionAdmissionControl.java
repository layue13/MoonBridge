package dev.strataproxy.network;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public final class ConnectionAdmissionControl {
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    private static final int RATE_CLEANUP_INTERVAL = 1024;

    private final int maxConnections;
    private final int maxConnectionsPerAddress;
    private final int maxNewConnectionsPerSecond;
    private final int maxNewConnectionsPerAddressPerSecond;
    private final LongSupplier nanoTime;
    private final RateWindow globalRate = new RateWindow();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger admissionAttempts = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> activeByAddress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> rateByAddress = new ConcurrentHashMap<>();

    public ConnectionAdmissionControl(int maxConnections, int maxConnectionsPerAddress) {
        this(maxConnections, maxConnectionsPerAddress, 0, 0);
    }

    public ConnectionAdmissionControl(
            int maxConnections,
            int maxConnectionsPerAddress,
            int maxNewConnectionsPerSecond,
            int maxNewConnectionsPerAddressPerSecond) {
        this(
                maxConnections,
                maxConnectionsPerAddress,
                maxNewConnectionsPerSecond,
                maxNewConnectionsPerAddressPerSecond,
                System::nanoTime);
    }

    ConnectionAdmissionControl(
            int maxConnections,
            int maxConnectionsPerAddress,
            int maxNewConnectionsPerSecond,
            int maxNewConnectionsPerAddressPerSecond,
            LongSupplier nanoTime) {
        if (maxConnections <= 0 || maxConnectionsPerAddress <= 0) {
            throw new IllegalArgumentException("connection limits must be positive");
        }
        if (maxNewConnectionsPerSecond < 0 || maxNewConnectionsPerAddressPerSecond < 0) {
            throw new IllegalArgumentException("connection rate limits must be >= 0");
        }
        this.maxConnections = maxConnections;
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
        this.maxNewConnectionsPerSecond = maxNewConnectionsPerSecond;
        this.maxNewConnectionsPerAddressPerSecond = maxNewConnectionsPerAddressPerSecond;
        this.nanoTime = nanoTime;
    }

    public Admission acquire(SocketAddress remoteAddress) {
        var now = nanoTime.getAsLong();
        var key = key(remoteAddress);
        if (maxNewConnectionsPerAddressPerSecond > 0) {
            var addressRate = rateByAddress.computeIfAbsent(key, ignored -> new RateWindow());
            if (!addressRate.tryAcquire(maxNewConnectionsPerAddressPerSecond, now)) {
                maybeCleanupRateBuckets(now);
                return Admission.rejected(key, "per_address_rate_limit");
            }
        }
        if (!globalRate.tryAcquire(maxNewConnectionsPerSecond, now)) {
            maybeCleanupRateBuckets(now);
            return Admission.rejected(key, "global_rate_limit");
        }
        if (!tryIncrement(activeConnections, maxConnections)) {
            maybeCleanupRateBuckets(now);
            return Admission.rejected(key, "global_limit");
        }
        var addressCounter = activeByAddress.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (!tryIncrement(addressCounter, maxConnectionsPerAddress)) {
            activeConnections.decrementAndGet();
            maybeCleanupRateBuckets(now);
            return Admission.rejected(key, "per_address_limit");
        }
        maybeCleanupRateBuckets(now);
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

    private void maybeCleanupRateBuckets(long now) {
        if (maxNewConnectionsPerAddressPerSecond <= 0) {
            return;
        }
        if ((admissionAttempts.incrementAndGet() & (RATE_CLEANUP_INTERVAL - 1)) != 0) {
            return;
        }
        var staleBefore = now - RATE_WINDOW_NANOS * 2;
        for (var entry : rateByAddress.entrySet()) {
            if (activeConnectionsFor(entry.getKey()) == 0 && entry.getValue().lastTouchedNanos() < staleBefore) {
                rateByAddress.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private static final class RateWindow {
        private long windowStartNanos = Long.MIN_VALUE;
        private long lastTouchedNanos = Long.MIN_VALUE;
        private int count;

        synchronized boolean tryAcquire(int limit, long now) {
            if (limit <= 0) {
                return true;
            }
            lastTouchedNanos = now;
            if (windowStartNanos == Long.MIN_VALUE || now - windowStartNanos >= RATE_WINDOW_NANOS || now < windowStartNanos) {
                windowStartNanos = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }

        synchronized long lastTouchedNanos() {
            return lastTouchedNanos;
        }
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
