package dev.strataproxy.infrastructure.minecraft;

import dev.strataproxy.infrastructure.minecraft.codec.MinecraftVarInts;
import dev.strataproxy.plugin.service.PluginMessageResult;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Encodes and writes plugin messages to an already connected backend channel. */
final class BackendPluginMessageSender {
    private static final int MAX_CHANNEL_BYTES = 128;

    private BackendPluginMessageSender() {
    }

    static CompletionStage<PluginMessageResult> send(
            Channel backend, MinecraftProtocolProfile profile, int maxFrameBytes, String channel, byte[] payload) {
        var result = new CompletableFuture<PluginMessageResult>();
        if (backend == null || !backend.isActive()) {
            result.complete(PluginMessageResult.failure("backend_unavailable"));
            return result;
        }
        backend.eventLoop().execute(() -> encodeAndWrite(backend, profile, maxFrameBytes, channel, payload, result));
        return result;
    }

    private static void encodeAndWrite(
            Channel backend, MinecraftProtocolProfile profile, int maxFrameBytes, String channel, byte[] payload,
            CompletableFuture<PluginMessageResult> result) {
        if (!validChannel(channel)) {
            result.complete(PluginMessageResult.failure("invalid_channel"));
            return;
        }
        var bytes = payload == null ? new byte[0] : payload.clone();
        if (bytes.length > maxFrameBytes || profile.serverboundCustomPayloadPacketId().isEmpty()) {
            result.complete(PluginMessageResult.failure("payload_too_large_or_unsupported"));
            return;
        }
        var packet = backend.alloc().buffer();
        try {
            MinecraftVarInts.write(packet, profile.serverboundCustomPayloadPacketId().getAsInt());
            writeString(packet, channel);
            MinecraftCustomPayloadBodyCodec.writeLength(packet, profile.serverboundCustomPayloadLengthFormat(), bytes.length);
            packet.writeBytes(bytes);
            var frame = frame(backend, packet);
            backend.writeAndFlush(frame).addListener(future -> result.complete(
                    future.isSuccess() ? PluginMessageResult.acceptedForWrite() : PluginMessageResult.failure("write_failed")));
        } catch (RuntimeException exception) {
            result.complete(PluginMessageResult.failure("encode_failed"));
        } finally {
            packet.release();
        }
    }

    private static boolean validChannel(String channel) {
        return channel != null && !channel.isBlank() && channel.getBytes(StandardCharsets.UTF_8).length <= MAX_CHANNEL_BYTES;
    }

    private static ByteBuf frame(Channel backend, ByteBuf packet) {
        var frame = backend.alloc().buffer(MinecraftVarInts.encodedSize(packet.readableBytes()) + packet.readableBytes());
        MinecraftVarInts.write(frame, packet.readableBytes());
        frame.writeBytes(packet);
        return frame;
    }

    private static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftVarInts.write(output, bytes.length);
        output.writeBytes(bytes);
    }
}
