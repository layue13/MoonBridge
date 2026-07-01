package dev.strataproxy.network;

import dev.strataproxy.codec.minecraft.MinecraftCompressionCodec;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSource;
import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.event.PlayerCommandEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.HashSet;
import java.util.Set;

final class GameCommandFrameInterceptor implements AutoCloseable {
    private static final Set<Integer> COMMAND_PACKET_CANDIDATES = Set.of(0x03, 0x04, 0x05);

    private final CommandRegistry commands;
    private final EventBus events;
    private final int maxFrameBytes;
    private final MinecraftCompressionCodec compressionCodec = new MinecraftCompressionCodec();
    private ByteBuf pending = Unpooled.buffer();

    GameCommandFrameInterceptor(CommandRegistry commands, EventBus events, int maxFrameBytes) {
        this.commands = commands;
        this.events = events;
        this.maxFrameBytes = maxFrameBytes;
    }

    Interception intercept(
            ByteBufAllocator allocator,
            ByteBuf input,
            boolean compressed,
            int compressionThreshold,
            CommandSource source) {
        if (commands == null || source == null || source.name().isBlank()) {
            return Interception.forward(input);
        }
        pending.writeBytes(input, input.readerIndex(), input.readableBytes());
        var outbound = allocator.buffer(pending.readableBytes());
        var consumed = false;
        try {
            while (pending.isReadable()) {
                var probe = MinecraftProtocolCodec.probeFrame(pending, maxFrameBytes);
                if (!probe.complete()) {
                    break;
                }
                var frame = pending.readRetainedSlice(probe.totalBytes());
                try {
                    var command = compressed
                            ? commandFromCompressedFrame(allocator, frame, compressionThreshold)
                            : commandFromUncompressedFrame(frame, probe.varIntBytes());
                    if (command == null) {
                        outbound.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
                        continue;
                    }
                    consumed = true;
                    if (events != null) {
                        events.publish(new PlayerCommandEvent(source.name(), command));
                    }
                    commands.execute(source, command).thenAccept(result -> sendResult(source, result));
                } finally {
                    frame.release();
                }
            }
            if (!pending.isReadable()) {
                pending.clear();
            } else if (pending.readerIndex() > 0) {
                pending.discardReadBytes();
            }
            if (!consumed && outbound.readableBytes() == input.readableBytes() && pending.readableBytes() == 0) {
                outbound.release();
                return Interception.forward(input);
            }
            if (!outbound.isReadable()) {
                outbound.release();
                return Interception.suppress();
            }
            return Interception.forward(outbound);
        } catch (RuntimeException exception) {
            outbound.release();
            releasePending();
            return Interception.forward(input);
        }
    }

    private static void sendResult(CommandSource source, CommandResult result) {
        if (result != null && result.handled() && !result.message().isBlank()) {
            source.sendMessage(result.message());
        }
    }

    private String commandFromCompressedFrame(ByteBufAllocator allocator, ByteBuf frame, int compressionThreshold) {
        if (compressionThreshold < 0) {
            return null;
        }
        var duplicate = frame.retainedDuplicate();
        ByteBuf decoded = null;
        try {
            decoded = compressionCodec.decodeFrame(allocator, duplicate, compressionThreshold, maxFrameBytes);
            return commandFromPacket(decoded);
        } finally {
            duplicate.release();
            if (decoded != null) {
                decoded.release();
            }
        }
    }

    private String commandFromUncompressedFrame(ByteBuf frame, int frameLengthBytes) {
        var packet = frame.retainedDuplicate();
        try {
            packet.skipBytes(frameLengthBytes);
            return commandFromPacket(packet);
        } finally {
            packet.release();
        }
    }

    private String commandFromPacket(ByteBuf packet) {
        var view = packet.retainedDuplicate();
        try {
            var packetId = MinecraftProtocolCodec.readVarInt(view);
            if (!COMMAND_PACKET_CANDIDATES.contains(packetId)) {
                return null;
            }
            var text = MinecraftProtocolCodec.readString(view, 256);
            var normalized = text.startsWith("/") ? text.substring(1) : text;
            var label = normalized.strip().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
            if (label.isBlank()) {
                return null;
            }
            if (!knownCommandLabel(label)) {
                return null;
            }
            return normalized;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            view.release();
        }
    }

    private boolean knownCommandLabel(String label) {
        var names = new HashSet<String>();
        for (var command : commands.commands()) {
            names.add(command.name());
            names.addAll(command.aliases());
        }
        return names.contains(label);
    }

    @Override
    /** Provides close. */
    public void close() {
        compressionCodec.close();
        releasePending();
    }

    private void releasePending() {
        if (pending.refCnt() > 0) {
            pending.release();
        }
        pending = Unpooled.EMPTY_BUFFER;
    }

    record Interception(boolean forward, ByteBuf message) {
        static Interception forward(ByteBuf message) {
            return new Interception(true, message);
        }

        static Interception suppress() {
            return new Interception(false, null);
        }
    }
}
