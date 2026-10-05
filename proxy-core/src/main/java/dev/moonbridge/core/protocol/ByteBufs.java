package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The two ownership patterns every encoder needs, written once. Leak detection is off in production,
 * so refcount correctness has to come from structure and tests rather than from a runtime detector.
 */
public final class ByteBufs {
    private ByteBufs() { }

    /** Fills a freshly allocated buffer and returns it, releasing it if filling throws. */
    public static ByteBuf fill(ByteBuf buffer, Consumer<ByteBuf> writer) {
        try {
            writer.accept(buffer);
            return buffer;
        } catch (Throwable failure) {
            buffer.release();
            throw failure;
        }
    }

    /** Runs {@code body} on a scratch buffer that is released whether or not the body succeeds. */
    public static <T> T use(ByteBuf scratch, Function<ByteBuf, T> body) {
        try {
            return body.apply(scratch);
        } finally {
            scratch.release();
        }
    }

    /** Writes {@code packet} into {@code output} as one length-prefixed frame. */
    public static void writeFrame(ByteBuf output, ByteBuf packet) {
        ProtocolVarInt.write(output, packet.readableBytes());
        output.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
    }
}
