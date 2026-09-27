package dev.moonbridge.core.session;

import dev.moonbridge.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Keeps connection-local PLAY keep-alive replies on the backend that issued them. */
final class KeepAliveBridge extends ChannelInboundHandlerAdapter {
    static final class State {
        private static final int MAX_PENDING = 1024;
        private int nextClientId = ThreadLocalRandom.current().nextInt();
        private final Map<Integer, Integer> pending = new HashMap<>();

        void switchBackend() {
            pending.clear();
        }

        /** Transforms a packet body while the login frame encoder is still installed. */
        ByteBuf body(ByteBufAllocator allocator, ByteBuf packet, boolean fromFrontend) {
            int start = packet.readerIndex();
            if (packet.readableBytes() == 0 || packet.getUnsignedByte(start) != 0) return packet;
            if (packet.readableBytes() != 5) throw new IllegalArgumentException("invalid keep-alive packet");
            Integer mapped = map(packet.getInt(start + 1), fromFrontend);
            if (mapped == null) return null;
            return allocator.buffer(5, 5).writeByte(0).writeInt(mapped);
        }

        /** Transforms a complete length-prefixed PLAY frame without copying ordinary packets. */
        ByteBuf frame(ByteBufAllocator allocator, ByteBuf packet, boolean fromFrontend) {
            int start = packet.readerIndex();
            if (packet.readableBytes() < 2 || packet.getUnsignedByte(start) != 5
                    || packet.getUnsignedByte(start + 1) != 0) return packet;
            if (packet.readableBytes() != 6) throw new IllegalArgumentException("invalid keep-alive frame");
            Integer mapped = map(packet.getInt(start + 2), fromFrontend);
            if (mapped == null) return null;
            return allocator.buffer(6, 6).writeByte(5).writeByte(0).writeInt(mapped);
        }

        private Integer map(int incomingId, boolean fromFrontend) {
            if (fromFrontend) {
                return pending.remove(incomingId);
            }
            if (pending.size() >= MAX_PENDING) {
                throw new IllegalStateException("too many unanswered backend keep-alives");
            }
            int generated;
            do {
                generated = ++nextClientId;
            } while (generated == incomingId || pending.containsKey(generated));
            pending.put(generated, incomingId);
            return generated;
        }
    }

    private final State state;
    private final boolean fromFrontend;
    private final Runnable closeSession;

    KeepAliveBridge(State state, boolean fromFrontend, Runnable closeSession) {
        this.state = state;
        this.fromFrontend = fromFrontend;
        this.closeSession = closeSession;
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf packet)) {
            ReferenceCountUtil.release(message);
            closeSession.run();
            return;
        }
        ByteBuf outgoing = packet;
        try {
            ByteBuf mapped = state.frame(ctx.alloc(), packet, fromFrontend);
            if (mapped != packet) {
                packet.release();
                outgoing = mapped;
            }
            if (outgoing != null) ctx.fireChannelRead(outgoing);
            else RawRelay.continueAfterDrop(ctx.channel());
            outgoing = null;
        } catch (RuntimeException malformed) {
            closeSession.run();
        } finally {
            if (outgoing != null) outgoing.release();
        }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (fromFrontend) closeSession.run();
        else ctx.close();
    }
}
