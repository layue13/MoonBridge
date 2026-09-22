package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.domain.compression.CompressionAction;
import dev.strataproxy.domain.compression.CompressionContext;
import dev.strataproxy.domain.compression.CompressionStrategies;
import dev.strataproxy.domain.compression.CompressionStrategy;
import dev.strataproxy.domain.protocol.PacketDirection;
import dev.strataproxy.domain.protocol.PacketView;
import dev.strataproxy.domain.protocol.ProtocolState;

final class CompressionRuntime {
    private final CompressionStrategy strategy;
    private final int minThreshold;
    private final int maxThreshold;
    private final double cpuGuard;

    CompressionRuntime(CompressionStrategy strategy, int minThreshold, int maxThreshold, double cpuGuard) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        if (minThreshold < 0 || maxThreshold < minThreshold) {
            throw new IllegalArgumentException("invalid compression threshold bounds");
        }
        if (cpuGuard < 0.0d || cpuGuard > 1.0d) {
            throw new IllegalArgumentException("cpuGuard must be between 0 and 1");
        }
        this.strategy = strategy;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
        this.cpuGuard = cpuGuard;
    }

    static CompressionRuntime defaults() {
        return new CompressionRuntime(CompressionStrategies.from("adaptive"), 256, 8192, 0.75d);
    }

    CompressionAction recordDecision(
            ProxyMetrics metrics,
            String serverName,
            ProxyMetrics.CompressionDirection direction,
            long rawBytes,
            double historicalCompressionRatio,
            long eventLoopDelayNanos) {
        if (rawBytes > Integer.MAX_VALUE) {
            var bypass = new CompressionAction.Bypass("packet too large for compression context");
            metrics.compressionDecision(serverName, direction, actionName(bypass), actionThreshold(bypass));
            return bypass;
        }
        var action = choose(direction, rawBytes, historicalCompressionRatio, eventLoopDelayNanos);
        metrics.compressionDecision(serverName, direction, actionName(action), actionThreshold(action));
        return action;
    }

    CompressionAction choose(
            ProxyMetrics.CompressionDirection direction,
            long rawBytes,
            double historicalCompressionRatio,
            long eventLoopDelayNanos) {
        if (rawBytes > Integer.MAX_VALUE) {
            return new CompressionAction.Bypass("packet too large for compression context");
        }
        var packetDirection = direction == ProxyMetrics.CompressionDirection.BACKEND_TO_FRONTEND
                ? PacketDirection.CLIENTBOUND
                : PacketDirection.SERVERBOUND;
        var eventLoopDelayMillis = Math.max(0L, eventLoopDelayNanos / 1_000_000L);
        var context = new CompressionContext(
                new PacketView(packetDirection, ProtocolState.PLAY, -1, -1, (int) rawBytes, 0, false),
                0,
                eventLoopDelayMillis,
                Math.min(1.0d, eventLoopDelayMillis / 1000.0d),
                cpuGuard,
                historicalCompressionRatio,
                minThreshold,
                maxThreshold);
        return strategy.choose(context);
    }

    int targetThreshold(CompressionAction action) {
        return actionThreshold(action);
    }

    private static String actionName(CompressionAction action) {
        return switch (action) {
            case CompressionAction.Bypass ignored -> "bypass";
            case CompressionAction.Threshold ignored -> "threshold";
            case CompressionAction.Force ignored -> "force";
        };
    }

    private static int actionThreshold(CompressionAction action) {
        return switch (action) {
            case CompressionAction.Bypass ignored -> -1;
            case CompressionAction.Threshold threshold -> threshold.bytes();
            case CompressionAction.Force force -> force.thresholdBytes();
        };
    }
}
