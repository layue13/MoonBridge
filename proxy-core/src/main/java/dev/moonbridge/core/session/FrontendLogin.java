package dev.moonbridge.core.session;

import dev.moonbridge.core.auth.AuthenticatedEncryption;
import dev.moonbridge.core.auth.MinecraftCipherDecoder;
import dev.moonbridge.core.auth.MinecraftCipherEncoder;
import dev.moonbridge.core.auth.MinecraftEncryptionRequest;
import dev.moonbridge.core.auth.MinecraftEncryptionResponse;
import dev.moonbridge.core.auth.OnlineModeCrypto;
import dev.moonbridge.core.auth.VerifiedProfile;
import dev.moonbridge.core.protocol.LoginStart;
import dev.moonbridge.core.protocol.MinecraftFrameDecoder;
import dev.moonbridge.core.protocol.MinecraftHandshake;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import dev.moonbridge.core.session.play.FrameTransformHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * The client-facing half of connecting: handshake, server-list status, login start and (online mode)
 * encryption and Mojang verification. It ends by reporting who the player is; everything after that
 * (permissions, admission, placement) belongs to the session. Confined to the frontend event loop.
 */
final class FrontendLogin {
    private static final String LOGIN_WAIT_GUARD = "login-wait-guard";

    private final ProxySessionListener owner;
    private final Channel frontend;
    private final Runnable closeSession;
    private final BooleanSupplier shuttingDown;
    private final BiConsumer<UUID, String> authenticated;
    private MinecraftHandshake handshake;
    private boolean statusRequest;
    private LoginStart loginStart;
    private MinecraftEncryptionRequest encryptionRequest;
    private VerifiedProfile verifiedProfile;
    private CompletableFuture<Optional<VerifiedProfile>> verification;

    FrontendLogin(ProxySessionListener owner, Channel frontend, Runnable closeSession,
                  BooleanSupplier shuttingDown, BiConsumer<UUID, String> authenticated) {
        this.owner = owner;
        this.frontend = frontend;
        this.closeSession = closeSession;
        this.shuttingDown = shuttingDown;
        this.authenticated = authenticated;
    }

    MinecraftHandshake handshake() { return handshake; }
    LoginStart loginStart() { return loginStart; }
    VerifiedProfile verifiedProfile() { return verifiedProfile; }

    void cancel() {
        if (verification != null) verification.cancel(true);
    }

    /** Any frame the client sends while the proxy is still deciding about the login is a protocol error. */
    void guardPendingLogin() {
        ChannelPipeline pipeline = frontend.pipeline();
        if (pipeline.get(LOGIN_WAIT_GUARD) == null) {
            pipeline.addFirst(LOGIN_WAIT_GUARD, new FrameTransformHandler(closeSession) {
                @Override protected ByteBuf transform(ChannelHandlerContext ctx, ByteBuf frame) {
                    throw new IllegalStateException("client sent data while login was pending");
                }
            });
        }
        frontend.config().setAutoRead(true);
    }

    void releaseGuard() {
        if (frontend.pipeline().get(LOGIN_WAIT_GUARD) != null) frontend.pipeline().remove(LOGIN_WAIT_GUARD);
    }

    void receive(ByteBuf packet) {
        if (handshake == null) {
            receiveHandshake(packet);
            return;
        }
        if (statusRequest) {
            receiveStatus(packet);
            return;
        }
        if (encryptionRequest != null) {
            receiveEncryptionResponse(packet);
            return;
        }
        if (loginStart != null) {
            closeSession.run();
            return;
        }
        loginStart = LoginStart.decode(packet, ProtocolProfile.minecraft1710());
        if (owner.onlineMode()) {
            encryptionRequest = owner.newEncryptionRequest();
            frontend.writeAndFlush(encryptionRequest.encode(frontend.alloc())).addListener(write -> {
                if (!write.isSuccess()) closeSession.run();
            });
            return;
        }
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + loginStart.username()).getBytes(StandardCharsets.UTF_8));
        authenticated.accept(uuid, loginStart.username());
    }

    private void receiveHandshake(ByteBuf packet) {
        handshake = MinecraftHandshake.decode(packet, ProtocolProfile.minecraft1710());
        if (handshake.nextState() == MinecraftHandshake.NextState.LOGIN) {
            int separator = handshake.serverAddress().indexOf('\0');
            if (separator >= 0) {
                handshake = new MinecraftHandshake(handshake.protocolVersion(),
                        handshake.serverAddress().substring(0, separator), handshake.serverPort(), handshake.nextState());
            }
        }
        if (handshake.protocolVersion() != ProtocolProfile.PROTOCOL_1_7_10) closeSession.run();
        else statusRequest = handshake.nextState() == MinecraftHandshake.NextState.STATUS;
    }

    private void receiveStatus(ByteBuf packet) {
        int id = ProtocolVarInt.read(packet.duplicate());
        if (id == 0) {
            frontend.writeAndFlush(owner.serverListStatus().encode(frontend.alloc(), owner.onlineCount()));
        } else if (id == 1) {
            ByteBuf input = packet.duplicate();
            ProtocolVarInt.read(input);
            if (input.readableBytes() != 8) throw new IllegalArgumentException("bad status ping");
            ByteBuf pong = frontend.alloc().buffer(9);
            ProtocolVarInt.write(pong, 1);
            pong.writeLong(input.readLong());
            frontend.writeAndFlush(pong).addListener(ignored -> frontend.close());
        } else closeSession.run();
    }

    private void receiveEncryptionResponse(ByteBuf packet) {
        MinecraftEncryptionResponse response = MinecraftEncryptionResponse.decode(packet);
        AuthenticatedEncryption accepted;
        try {
            accepted = OnlineModeCrypto.decrypt(owner.encryptionKeys().getPrivate(), encryptionRequest, response);
        } catch (GeneralSecurityException failure) {
            closeSession.run();
            return;
        }
        byte[] secret = accepted.sharedSecret();
        String serverHash = OnlineModeCrypto.serverHash(encryptionRequest.serverId(), secret,
                encryptionRequest.publicKey());
        encryptionRequest = null;
        ChannelPipeline pipeline = frontend.pipeline();
        try {
            pipeline.addAfter("minecraft-frame-decoder", "minecraft-cipher-decoder", new MinecraftCipherDecoder(secret));
            pipeline.addAfter("minecraft-cipher-decoder", "encrypted-frame-decoder",
                    new MinecraftFrameDecoder(ProtocolProfile.minecraft1710(), true,
                            ProtocolProfile.MAX_LOGIN_FRAME_BYTES));
            pipeline.addBefore("minecraft-frame-encoder", "minecraft-cipher-encoder", new MinecraftCipherEncoder(secret));
            pipeline.remove("minecraft-frame-decoder");
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        guardPendingLogin();
        String clientIp = ((InetSocketAddress) frontend.remoteAddress()).getAddress().getHostAddress();
        verification = owner.verifier().verify(loginStart.username(), serverHash, clientIp).toCompletableFuture();
        verification.whenComplete((profile, failure) -> frontend.eventLoop().execute(() -> {
            verification = null;
            if (shuttingDown.getAsBoolean()) return;
            if (failure != null || profile == null || profile.isEmpty()) {
                closeSession.run();
                return;
            }
            verifiedProfile = profile.get();
            if (!verifiedProfile.username().equalsIgnoreCase(loginStart.username())) {
                closeSession.run();
                return;
            }
            authenticated.accept(verifiedProfile.uuid(), verifiedProfile.username());
        }));
    }
}
