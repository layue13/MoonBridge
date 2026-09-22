package dev.strataproxy.network;

import dev.strataproxy.compression.CompressionAction;
import dev.strataproxy.compression.CompressionContext;
import dev.strataproxy.compression.CompressionStrategies;
import dev.strataproxy.compression.CompressionStrategy;
import dev.strataproxy.protocol.PacketDirection;
import dev.strataproxy.protocol.PacketView;
import dev.strataproxy.protocol.ProtocolState;

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
            RelayDirection direction,
            long rawBytes,
            double historicalCompressionRatio,
            long eventLoopDelayNanos) {
        if (rawBytes > Integer.MAX_VALUE) {
            var bypass = new CompressionAction.Bypass("packet too large for compression context");
            return bypass;
        }
        var action = choose(direction, rawBytes, historicalCompressionRatio, eventLoopDelayNanos);
        return action;
    }

    CompressionAction choose(
            RelayDirection direction,
            long rawBytes,
            double historicalCompressionRatio,
            long eventLoopDelayNanos) {
        if (rawBytes > Integer.MAX_VALUE) {
            return new CompressionAction.Bypass("packet too large for compression context");
        }
        var packetDirection = direction == RelayDirection.BACKEND_TO_FRONTEND
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

    private static int actionThreshold(CompressionAction action) {
        return switch (action) {
            case CompressionAction.Bypass ignored -> -1;
            case CompressionAction.Threshold threshold -> threshold.bytes();
            case CompressionAction.Force force -> force.thresholdBytes();
        };
    }
}
