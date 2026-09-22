package dev.strataproxy.network;

import dev.strataproxy.compression.CompressionAction;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

/** Shared compressed-frame accounting for both directions of a relay session. */
final class RelayCompressionObserver {
    private RelayCompressionObserver() {
    }

    static Observation observe(
            MinecraftCompressionAuditState audit,
            ByteBuf buffer,
            boolean backendToFrontend,
            CompressionRuntime runtime,
            ProxyMetrics metrics) {
        if (!audit.negotiated()) {
            return Observation.allow();
        }
        try {
            var samples = backendToFrontend ? audit.observeBackend(buffer) : audit.observeFrontend(buffer);
            if (samples.isEmpty()) {
                return Observation.rewriteAllow();
            }
            var direction = backendToFrontend
                    ? RelayDirection.BACKEND_TO_FRONTEND
                    : RelayDirection.FRONTEND_TO_BACKEND;
            var actions = new ArrayList<CompressionAction>(samples.size());
            for (var sample : samples) {
                actions.add(runtime.recordDecision(
                        direction,
                        sample.rawBytes(),
                        ratio(sample),
                        0));
            }
            return new Observation(true, true, actions);
        } catch (RuntimeException exception) {
            if (backendToFrontend) {
                audit.closeBackendSampler();
            } else {
                audit.closeFrontendSampler();
            }
            return Observation.block();
        }
    }

    private static double ratio(MinecraftCompressedFrameAuditSampler.CompressionFrameSample sample) {
        return sample.rawBytes() == 0 ? 1.0d : (double) sample.compressedBytes() / sample.rawBytes();
    }

    record Observation(boolean shouldForward, boolean rewriteEligible, List<CompressionAction> actions) {
        private static final Observation ALLOW = new Observation(true, false, List.of());
        private static final Observation REWRITE_ALLOW = new Observation(true, true, List.of());
        private static final Observation BLOCK = new Observation(false, false, List.of());

        static Observation allow() {
            return ALLOW;
        }

        static Observation rewriteAllow() {
            return REWRITE_ALLOW;
        }

        static Observation block() {
            return BLOCK;
        }
    }
}
