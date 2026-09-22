package dev.strataproxy.network;

import dev.strataproxy.compression.CompressionStrategies;
import dev.strataproxy.compression.CompressionStrategy;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;

import java.util.Objects;

/**
 * Immutable runtime services and policy for one Netty proxy listener.
 *
 * <p>Keeping optional protocol features together avoids a positional constructor
 * whose arguments are easy to miswire when the listener evolves.</p>
 */
public record NettyProxyRuntime(
        CompressionStrategy compressionStrategy,
        int compressionMinThreshold,
        int compressionMaxThreshold,
        double compressionCpuGuard,
        MinecraftAuthRuntime auth,
        MinecraftForwardingRuntime forwarding,
        MinecraftStatusRuntime status,
        boolean compressionRewriteEnabled,
        int compressionRewriteMaxEventLoopDelayMillis,
        CommandRegistry commands,
        EventBus events) {
    /** Validates required protocol policy and supplies disabled optional services. */
    public NettyProxyRuntime {
        compressionStrategy = Objects.requireNonNull(compressionStrategy, "compressionStrategy");
        auth = auth == null ? MinecraftAuthRuntime.offline() : auth;
        forwarding = forwarding == null ? MinecraftForwardingRuntime.none() : forwarding;
        status = status == null ? MinecraftStatusRuntime.disabled() : status;
        compressionRewriteMaxEventLoopDelayMillis = Math.max(0, compressionRewriteMaxEventLoopDelayMillis);
    }

    /** Creates the standard offline, no-forwarding runtime. */
    public static NettyProxyRuntime defaults() {
        return new NettyProxyRuntime(
                CompressionStrategies.from("adaptive"),
                256,
                8_192,
                0.75d,
                MinecraftAuthRuntime.offline(),
                MinecraftForwardingRuntime.none(),
                MinecraftStatusRuntime.disabled(),
                false,
                25,
                null,
                null);
    }

    CompressionRuntime compressionRuntime() {
        return new CompressionRuntime(
                compressionStrategy,
                compressionMinThreshold,
                compressionMaxThreshold,
                compressionCpuGuard);
    }
}
