package dev.strataproxy.infrastructure.minecraft;

import io.netty.buffer.ByteBuf;

import java.util.List;

final class MinecraftCompressionAuditState {
    private static final int UNNEGOTIATED = -1;

    private final int maxFrameBytes;
    private int threshold = UNNEGOTIATED;
    private MinecraftCompressedFrameAuditSampler frontendSampler;
    private MinecraftCompressedFrameAuditSampler backendSampler;

    MinecraftCompressionAuditState(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    boolean negotiated() {
        return threshold >= 0;
    }

    int threshold() {
        return threshold;
    }

    void negotiate(int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must be non-negative");
        }
        this.threshold = threshold;
    }

    List<MinecraftCompressedFrameAuditSampler.CompressionFrameSample> observeFrontend(ByteBuf input) {
        if (!negotiated()) {
            return List.of();
        }
        if (frontendSampler == null) {
            frontendSampler = new MinecraftCompressedFrameAuditSampler(threshold, maxFrameBytes);
        }
        return frontendSampler.observe(input);
    }

    List<MinecraftCompressedFrameAuditSampler.CompressionFrameSample> observeBackend(ByteBuf input) {
        if (!negotiated()) {
            return List.of();
        }
        if (backendSampler == null) {
            backendSampler = new MinecraftCompressedFrameAuditSampler(threshold, maxFrameBytes);
        }
        return backendSampler.observe(input);
    }

    void closeFrontendSampler() {
        if (frontendSampler != null) {
            frontendSampler.close();
            frontendSampler = null;
        }
    }

    void closeBackendSampler() {
        if (backendSampler != null) {
            backendSampler.close();
            backendSampler = null;
        }
    }

    void close() {
        closeFrontendSampler();
        closeBackendSampler();
    }
}
