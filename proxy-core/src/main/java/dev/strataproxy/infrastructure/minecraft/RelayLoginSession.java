package dev.strataproxy.infrastructure.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

final class RelayLoginSession {
    private final byte[] handshakeFrame;
    private final byte[] loginBytes;

    RelayLoginSession(ByteBuf handshakeFrame, ByteBuf loginBytes) {
        this.handshakeFrame = copy(handshakeFrame);
        this.loginBytes = copy(loginBytes);
    }

    boolean available() {
        return handshakeFrame.length > 0 && loginBytes.length > 0;
    }

    void writeTo(Channel backend) {
        backend.write(backend.alloc().buffer(handshakeFrame.length).writeBytes(handshakeFrame));
        backend.write(backend.alloc().buffer(loginBytes.length).writeBytes(loginBytes));
        backend.flush();
    }

    private static byte[] copy(ByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return new byte[0];
        }
        var bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }
}
