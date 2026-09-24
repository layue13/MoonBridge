package dev.strataproxy.core.session;

import dev.strataproxy.core.protocol.Minecraft1710EntityIds;
import dev.strataproxy.core.protocol.Minecraft1710PlayPackets;
import dev.strataproxy.core.protocol.ProtocolProfile;
import dev.strataproxy.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.OptionalInt;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Handles the few framed packets that cannot pass unchanged after a backend switch. */
final class TransferFrameHandler extends ChannelInboundHandlerAdapter {
    static final class State implements AutoCloseable {
        private final Channel frontend;
        private final Channel backend;
        private final PlayObservation observation;
        private final OptionalInt previousDimension;
        private final int clientEntityId;
        private final Runnable closeSession;
        private final ScheduledFuture<?> joinDeadline;
        private Integer serverEntityId;
        private boolean joined;

        State(Channel frontend, Channel backend, PlayObservation observation,
              OptionalInt previousDimension, int clientEntityId,
              Minecraft1710PlayPackets.JoinGame consumedJoinGame, Runnable closeSession) {
            this.frontend = frontend;
            this.backend = backend;
            this.observation = observation;
            this.previousDimension = previousDimension;
            this.clientEntityId = clientEntityId;
            this.closeSession = closeSession;
            if (consumedJoinGame == null) {
                joinDeadline = frontend.eventLoop().schedule(closeSession, 30, TimeUnit.SECONDS);
            } else {
                serverEntityId = consumedJoinGame.entityId();
                joined = true;
                joinDeadline = null;
            }
        }

        private void joinGame(ByteBuf packet) {
            if (joined) throw new IllegalArgumentException("duplicate replacement Join Game");
            Minecraft1710PlayPackets.JoinGame join = Minecraft1710PlayPackets.joinGame(packet)
                    .orElseThrow(() -> new IllegalArgumentException("expected replacement Join Game"));
            observation.observePacket(true, packet);
            int dimension = observation.dimension().orElse(join.dimension());
            ByteBuf respawns = Minecraft1710PlayPackets.respawnSequence(frontend.alloc(), join,
                    previousDimension, dimension);
            frontend.writeAndFlush(respawns).addListener(write -> {
                if (!write.isSuccess()) closeSession.run();
            });
            serverEntityId = join.entityId();
            joined = true;
            if (joinDeadline != null) joinDeadline.cancel(false);
        }

        @Override public void close() {
            if (joinDeadline != null) joinDeadline.cancel(false);
        }
    }

    private final State state;
    private final boolean clientbound;

    TransferFrameHandler(State state, boolean clientbound) {
        this.state = state;
        this.clientbound = clientbound;
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf frame)) {
            ReferenceCountUtil.release(message);
            state.closeSession.run();
            return;
        }
        ByteBuf outgoing = frame;
        try {
            ByteBuf body = frame.duplicate();
            int length = ProtocolVarInt.read(body);
            if (length < 1 || length != body.readableBytes()
                    || length > ProtocolProfile.minecraft1710().maxFrameBytes()) {
                throw new IllegalArgumentException("invalid transferred PLAY frame");
            }
            int packetId = ProtocolVarInt.read(body.duplicate());
            if (clientbound && packetId == Minecraft1710PlayPackets.JOIN_GAME) {
                state.joinGame(body);
                return;
            }
            Integer serverId = state.serverEntityId;
            if (serverId != null && serverId != state.clientEntityId) {
                ByteBuf rewritten = Minecraft1710EntityIds.rewrite(ctx.alloc(), frame, clientbound,
                        serverId, state.clientEntityId);
                if (rewritten != frame) {
                    outgoing = rewritten;
                    frame.release();
                }
            }
            ctx.fireChannelRead(outgoing);
            outgoing = null;
        } catch (RuntimeException failure) {
            state.closeSession.run();
        } finally {
            if (outgoing != null) outgoing.release();
        }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        state.closeSession.run();
    }
}
