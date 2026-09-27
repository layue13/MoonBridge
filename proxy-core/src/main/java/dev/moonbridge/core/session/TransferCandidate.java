package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.MinecraftLoginSuccess;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Logs into a replacement backend while the existing player session keeps its old backend. */
final class TransferCandidate extends ChannelInboundHandlerAdapter {
    interface Listener {
        void ready(TransferCandidate candidate);
        void failed(TransferCandidate candidate, String reason);
    }

    private static final int MAX_QUEUED_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes();
    private static final int MAX_QUEUED_PACKETS = 1024;
    private final UUID expectedId;
    private final String username;
    private final Listener listener;
    private final PlayObservation observation = new PlayObservation();
    private final ArrayDeque<ByteBuf> queued = new ArrayDeque<>();
    private Minecraft1710PlayPackets.JoinGame joinGame;
    private ScheduledFuture<?> deadline;
    private State state = State.LOGIN;
    private int queuedBytes;

    TransferCandidate(UUID expectedId, String username, Listener listener) {
        this.expectedId = expectedId;
        this.username = username;
        this.listener = listener;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        deadline = ctx.executor().schedule(() -> fail(ctx, "backend login timed out"), 15, TimeUnit.SECONDS);
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf frame)) {
            ReferenceCountUtil.release(message);
            fail(ctx, "unexpected backend message type");
            return;
        }
        try {
            ByteBuf packet = frame.duplicate();
            int frameLength = ProtocolVarInt.read(packet);
            if (frameLength < 1 || frameLength != packet.readableBytes()) {
                throw new IllegalArgumentException("invalid backend transfer frame");
            }
            if (state == State.FAILED || state == State.HANDED_OFF) return;
            if (state == State.LOGIN) {
                int id = ProtocolVarInt.read(packet.duplicate());
                if (id != 2) {
                    fail(ctx, id == 0 ? "backend rejected login" : "unexpected backend login packet");
                    return;
                }
                MinecraftLoginSuccess success = MinecraftLoginSuccess.decode(packet);
                if (!expectedId.equals(success.playerId()) || !username.equals(success.username())) {
                    fail(ctx, "backend identity did not match the player session");
                    return;
                }
                state = State.PLAY;
                return;
            }
            if (state == State.PLAY || state == State.READY) {
                if (ProtocolVarInt.read(packet.duplicate()) == Minecraft1710PlayPackets.SERVER_DISCONNECT) {
                    fail(ctx, "replacement backend rejected player before transfer");
                    return;
                }
                var login = Minecraft1710PlayPackets.joinGame(packet);
                if (login.isPresent()) {
                    if (joinGame != null) throw new IllegalArgumentException("duplicate Join Game from backend");
                    joinGame = login.get();
                } else {
                    int bytes = packet.readableBytes();
                    if (queued.size() >= MAX_QUEUED_PACKETS || bytes > MAX_QUEUED_BYTES - queuedBytes) {
                        fail(ctx, "backend sent too much data before transfer");
                        return;
                    }
                    queued.addLast(packet.retainedDuplicate());
                    queuedBytes += bytes;
                }
                observation.observePacket(true, packet);
                // Forge needs the client to answer ServerHello; a vanilla Join Game already
                // provides the world and entity ID needed to begin the network cutover.
                if (state == State.PLAY && (observation.forgeSeen() || joinGame != null)) {
                    state = State.READY;
                    ctx.channel().config().setAutoRead(false);
                    deadline.cancel(false);
                    listener.ready(this);
                }
            }
        } catch (RuntimeException malformed) {
            fail(ctx, "malformed backend transfer packet");
        } finally {
            frame.release();
        }
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        if (state != State.HANDED_OFF) fail(ctx, "replacement backend disconnected");
        ctx.fireChannelInactive();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (state == State.HANDED_OFF) ctx.close();
        else fail(ctx, "replacement backend failed");
    }

    Minecraft1710PlayPackets.JoinGame joinGame() { return joinGame; }
    PlayObservation observation() { return observation; }

    List<ByteBuf> takeQueuedPackets() {
        var packets = new ArrayList<>(queued);
        queued.clear();
        queuedBytes = 0;
        return packets;
    }

    void handOff() {
        if (state != State.READY) throw new IllegalStateException("replacement backend is not ready");
        state = State.HANDED_OFF;
        deadline.cancel(false);
    }

    void close() {
        if (deadline != null) deadline.cancel(false);
        ByteBuf packet;
        while ((packet = queued.pollFirst()) != null) packet.release();
        queuedBytes = 0;
        observation.close();
    }

    private void fail(ChannelHandlerContext ctx, String reason) {
        if (state == State.FAILED || state == State.HANDED_OFF) return;
        state = State.FAILED;
        close();
        listener.failed(this, reason);
        ctx.close();
    }

    private enum State { LOGIN, PLAY, READY, HANDED_OFF, FAILED }
}
