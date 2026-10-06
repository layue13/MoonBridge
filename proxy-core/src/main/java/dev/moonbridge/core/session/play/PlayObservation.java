package dev.moonbridge.core.session.play;

import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;

import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/** PLAY facts for a session; callers run on the player's event loop. */
public final class PlayObservation implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private boolean joinSeen;
    private boolean forgeSeen;
    private boolean backendComplete;
    private boolean clientComplete;
    private OptionalInt dimension = OptionalInt.empty();
    private OptionalInt forgeDimensionOverride = OptionalInt.empty();
    private OptionalInt entityId = OptionalInt.empty();
    /** Called after MinecraftFrameDecoder with one complete, length-prefixed frame. */
    public void observeFrame(boolean clientbound, ByteBuf frame) {
        if (ready.isDone()) return;
        ByteBuf packet = frame.duplicate();
        int length = ProtocolVarInt.read(packet);
        if (length < 1 || length > MAX_FRAME_BYTES || length != packet.readableBytes()) {
            throw new IllegalArgumentException("invalid PLAY frame");
        }
        observePacket(clientbound, packet);
    }

    public void observePacket(boolean clientbound, ByteBuf packet) {
        if (ready.isDone()) return;
        int id = ProtocolVarInt.read(packet.duplicate());
        if (clientbound) {
            Minecraft1710PlayPackets.joinGame(packet).ifPresent(join -> {
                joinSeen = true;
                entityId = OptionalInt.of(join.entityId());
                dimension = forgeDimensionOverride.isPresent() ? forgeDimensionOverride
                        : OptionalInt.of(join.dimension());
            });
            Minecraft1710PlayPackets.forgeHandshake(packet, true).ifPresent(handshake -> {
                forgeSeen = true;
                handshake.hello().ifPresent(hello -> {
                    if (hello.dimensionOverride() != 0) {
                        forgeDimensionOverride = OptionalInt.of(hello.dimensionOverride());
                        dimension = forgeDimensionOverride;
                    }
                });
                if (handshake.discriminator() == 0xFF
                        && handshake.phase().isPresent() && handshake.phase().getAsInt() == 3) {
                    backendComplete = true;
                }
            });
            if (joinSeen && !forgeSeen && id == 0x08) ready.complete(null); // Position and Look follows vanilla login.
        } else {
            Minecraft1710PlayPackets.forgeHandshake(packet, false).ifPresent(handshake -> {
                if (handshake.discriminator() == 0xFF
                        && handshake.phase().isPresent() && handshake.phase().getAsInt() == 5) {
                    clientComplete = true;
                }
            });
        }
        if (joinSeen && forgeSeen && backendComplete && clientComplete) ready.complete(null);
    }

    public CompletableFuture<Void> ready() { return ready; }
    public boolean forgeSeen() { return forgeSeen; }
    public OptionalInt dimension() { return dimension; }
    public OptionalInt entityId() { return entityId; }

    @Override public void close() {
        if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("session closed"));
    }
}
