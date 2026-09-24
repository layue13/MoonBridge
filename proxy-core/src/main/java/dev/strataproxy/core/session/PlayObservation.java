package dev.strataproxy.core.session;

import dev.strataproxy.core.protocol.Minecraft1710PlayPackets;
import dev.strataproxy.core.protocol.PacketStreamTap;
import dev.strataproxy.core.protocol.ProtocolProfile;
import dev.strataproxy.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;

import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/** Only the PLAY facts needed to safely start a later backend replacement. */
final class PlayObservation implements AutoCloseable {
    private final PacketStreamTap clientFrames = new PacketStreamTap(
            ProtocolProfile.minecraft1710().maxFrameBytes(), packet -> observePacket(false, packet));
    private final PacketStreamTap backendFrames = new PacketStreamTap(
            ProtocolProfile.minecraft1710().maxFrameBytes(), packet -> observePacket(true, packet));
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private boolean joinSeen;
    private boolean forgeSeen;
    private boolean backendComplete;
    private boolean clientComplete;
    private OptionalInt dimension = OptionalInt.empty();
    private OptionalInt forgeDimensionOverride = OptionalInt.empty();
    private OptionalInt entityId = OptionalInt.empty();

    void observeStream(boolean clientbound, ByteBuf bytes) {
        if (ready.isDone()) return;
        if (clientbound) backendFrames.accept(bytes);
        else clientFrames.accept(bytes);
    }

    void observePacket(boolean clientbound, ByteBuf packet) {
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

    CompletableFuture<Void> ready() { return ready; }
    boolean forgeSeen() { return forgeSeen; }
    OptionalInt dimension() { return dimension; }
    OptionalInt entityId() { return entityId; }

    @Override public void close() {
        clientFrames.close();
        backendFrames.close();
        if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("session closed"));
    }
}
