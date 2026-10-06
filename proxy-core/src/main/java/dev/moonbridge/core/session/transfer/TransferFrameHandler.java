package dev.moonbridge.core.session.transfer;

import dev.moonbridge.core.session.play.FrameTransformHandler;
import dev.moonbridge.core.session.play.PlayObservation;

import dev.moonbridge.core.protocol.Minecraft1710EntityIds;
import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import dev.moonbridge.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.CompletableFuture;

/** Handles the few framed packets that cannot pass unchanged after a backend switch. */
public final class TransferFrameHandler extends FrameTransformHandler {
    public static final class State implements AutoCloseable {
        private final Channel frontend;
        private final Channel backend;
        private final PlayObservation observation;
        private final int clientEntityId;
        private final Runnable closeSession;
        private final CompletableFuture<Void> worldReady = new CompletableFuture<>();
        private Integer serverEntityId;
        private boolean joined;

        State(Channel frontend, Channel backend, PlayObservation observation, int clientEntityId,
              Minecraft1710PlayPackets.JoinGame consumedJoinGame, Runnable closeSession) {
            this.frontend = frontend;
            this.backend = backend;
            this.observation = observation;
            this.clientEntityId = clientEntityId;
            this.closeSession = closeSession;
            if (consumedJoinGame != null) {
                serverEntityId = consumedJoinGame.entityId();
                joined = true;
                worldReady.complete(null);
            }
        }

        CompletableFuture<Void> worldReady() { return worldReady; }

        private boolean awaitingForgeWorld() {
            return observation.forgeSeen() && (!observation.ready().isDone() || !worldReady.isDone());
        }

        private boolean forgeControlPacket(ByteBuf packet, int packetId) {
            if (packetId == 0) return true; // The keep-alive bridge has already translated this reply.
            if (packetId != Minecraft1710PlayPackets.CLIENT_CUSTOM_PAYLOAD) return false;
            return Minecraft1710PlayPackets.forgeControlPayload(packet);
        }

        private void joinGame(ByteBuf packet) {
            if (joined) throw new IllegalArgumentException("duplicate replacement Join Game");
            Minecraft1710PlayPackets.JoinGame join = Minecraft1710PlayPackets.joinGame(packet)
                    .orElseThrow(() -> new IllegalArgumentException("expected replacement Join Game"));
            observation.observePacket(true, packet);
            int dimension = observation.dimension().orElse(join.dimension());
            ByteBuf respawns = Minecraft1710PlayPackets.respawnSequence(frontend.alloc(), join, dimension);
            serverEntityId = join.entityId();
            joined = true;
            frontend.writeAndFlush(respawns).addListener(write -> {
                if (write.isSuccess()) {
                    worldReady.complete(null);
                    RawRelay.continueAfterDrop(backend);
                } else {
                    worldReady.completeExceptionally(new IllegalStateException(
                            "could not write replacement world transition", write.cause()));
                    closeSession.run();
                }
            });
        }

        @Override public void close() {
            worldReady.completeExceptionally(new IllegalStateException("replacement world transition closed"));
        }
    }

    private final State state;
    private final boolean clientbound;

    TransferFrameHandler(State state, boolean clientbound) {
        super(state.closeSession);
        this.state = state;
        this.clientbound = clientbound;
    }

    @Override protected ByteBuf transform(ChannelHandlerContext ctx, ByteBuf frame) {
        ByteBuf body = frame.duplicate();
        int length = ProtocolVarInt.read(body);
        if (length < 1 || length != body.readableBytes()
                || length > ProtocolProfile.minecraft1710().maxFrameBytes()) {
            throw new IllegalArgumentException("invalid transferred PLAY frame");
        }
        int packetId = ProtocolVarInt.read(body.duplicate());
        if (clientbound && packetId == Minecraft1710PlayPackets.JOIN_GAME) {
            state.joinGame(body);
            return CONSUMED; // The relay resumes once the world transition has been written.
        }
        if (!clientbound && state.awaitingForgeWorld() && !state.forgeControlPacket(body, packetId)) {
            // Frames from the previous world may have been queued during cutover. Sending
            // movement or gameplay to a backend still joining the player is unsafe.
            return null;
        }
        Integer serverId = state.serverEntityId;
        if (serverId != null && serverId != state.clientEntityId) {
            return Minecraft1710EntityIds.rewrite(ctx.alloc(), frame, clientbound, serverId, state.clientEntityId);
        }
        return frame;
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        state.closeSession.run();
    }
}
