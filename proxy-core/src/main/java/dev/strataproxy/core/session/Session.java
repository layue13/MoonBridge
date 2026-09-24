package dev.strataproxy.core.session;

import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.PlayerIdentity;
import dev.strataproxy.api.PlayerView;
import dev.strataproxy.core.auth.AuthenticatedEncryption;
import dev.strataproxy.core.auth.MinecraftCipherDecoder;
import dev.strataproxy.core.auth.MinecraftCipherEncoder;
import dev.strataproxy.core.auth.MinecraftEncryptionRequest;
import dev.strataproxy.core.auth.MinecraftEncryptionResponse;
import dev.strataproxy.core.auth.OnlineModeCrypto;
import dev.strataproxy.core.auth.VerifiedProfile;
import dev.strataproxy.core.backend.BackendView;
import dev.strataproxy.core.backend.CapacityReservation;
import dev.strataproxy.core.forwarding.BungeeLegacyForwarding;
import dev.strataproxy.core.protocol.LoginStart;
import dev.strataproxy.core.protocol.MinecraftHandshake;
import dev.strataproxy.core.protocol.ProtocolProfile;
import dev.strataproxy.core.protocol.ProtocolVarInt;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

@io.netty.channel.ChannelHandler.Sharable
final class Session extends ChannelInboundHandlerAdapter {
    private static final int MAX_TRANSITION_BUFFER_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes();
    private final ProxySessionListener owner;
    private final Channel frontend;
    private volatile Channel backend;
    private MinecraftHandshake handshake;
    private LoginStart loginStart;
    private MinecraftEncryptionRequest encryptionRequest;
    private VerifiedProfile verifiedProfile;
    private boolean verifyingIdentity;
    private PlayerIdentity identity;
    private PlayerView view;
    private BackendView selected;
    private CapacityReservation reservation;
    private boolean placementInProgress;
    private boolean published;
    private boolean relayStarting;
    private int transitionBufferBytes;
    private final ArrayDeque<PendingFrame> transitionBuffer = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    Session(ProxySessionListener owner, Channel frontend) {
        this.owner = owner;
        this.frontend = frontend;
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf packet)) {
            ReferenceCountUtil.release(message);
            closePair();
            return;
        }
        try {
            if (closed.get()) return;
            if (relayStarting) {
                bufferTransitionFrame(ctx.channel() == frontend, packet);
                return;
            }
            if (ctx.channel() == frontend) receiveFrontend(packet);
            else receiveBackend(packet);
        } catch (RuntimeException failure) {
            closePair();
        } finally {
            packet.release();
        }
    }

    private void receiveFrontend(ByteBuf packet) {
        if (handshake == null) {
            handshake = MinecraftHandshake.decode(packet, ProtocolProfile.minecraft1710());
            if (handshake.protocolVersion() != ProtocolProfile.PROTOCOL_1_7_10) {
                closePair();
                return;
            }
            if (handshake.nextState() == MinecraftHandshake.NextState.STATUS) {
                statusRequest = true;
                return;
            }
            return;
        }
        if (statusRequest) {
            int id = ProtocolVarInt.read(packet.duplicate());
            if (id == 0) {
                sendStatus();
            } else if (id == 1) {
                ByteBuf input = packet.duplicate();
                ProtocolVarInt.read(input);
                if (input.readableBytes() != 8) throw new IllegalArgumentException("bad status ping");
                ByteBuf pong = frontend.alloc().buffer(9);
                ProtocolVarInt.write(pong, 1);
                pong.writeLong(input.readLong());
                frontend.writeAndFlush(pong).addListener(ignored -> frontend.close());
            } else closePair();
            return;
        }
        if (encryptionRequest != null && !verifyingIdentity) {
            receiveEncryptionResponse(packet);
            return;
        }
        if (loginStart != null || placementInProgress || verifyingIdentity) {
            closePair();
            return;
        }
        loginStart = LoginStart.decode(packet, ProtocolProfile.minecraft1710());
        if (owner.onlineMode()) {
            encryptionRequest = owner.newEncryptionRequest();
            frontend.writeAndFlush(encryptionRequest.encode(frontend.alloc())).addListener(write -> {
                if (!write.isSuccess()) closePair();
            });
            return;
        }
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + loginStart.username()).getBytes(StandardCharsets.UTF_8));
        beginPlacement(uuid, loginStart.username());
    }

    private void receiveEncryptionResponse(ByteBuf packet) {
        MinecraftEncryptionResponse response = MinecraftEncryptionResponse.decode(packet);
        AuthenticatedEncryption accepted;
        try {
            accepted = OnlineModeCrypto.decrypt(owner.encryptionKeys().getPrivate(), encryptionRequest, response);
        } catch (GeneralSecurityException failure) {
            closePair();
            return;
        }
        byte[] secret = accepted.sharedSecret();
        String serverHash = OnlineModeCrypto.serverHash(encryptionRequest.serverId(), secret,
                encryptionRequest.publicKey());
        verifyingIdentity = true;
        frontend.config().setAutoRead(false);
        ChannelPipeline pipeline = frontend.pipeline();
        try {
            pipeline.addAfter("minecraft-frame-decoder", "minecraft-cipher-decoder", new MinecraftCipherDecoder(secret));
            pipeline.addAfter("minecraft-cipher-decoder", "encrypted-frame-decoder",
                    new dev.strataproxy.core.protocol.MinecraftFrameDecoder(ProtocolProfile.minecraft1710()));
            pipeline.addBefore("minecraft-frame-encoder", "minecraft-cipher-encoder", new MinecraftCipherEncoder(secret));
            pipeline.remove("minecraft-frame-decoder");
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
        String clientIp = ((InetSocketAddress) frontend.remoteAddress()).getAddress().getHostAddress();
        owner.verifier().verify(loginStart.username(), serverHash, clientIp)
                .whenComplete((profile, failure) -> frontend.eventLoop().execute(() -> {
                    if (closed.get()) return;
                    if (failure != null || profile == null || profile.isEmpty()) {
                        closePair();
                        return;
                    }
                    verifiedProfile = profile.get();
                    if (!verifiedProfile.username().equalsIgnoreCase(loginStart.username())) {
                        closePair();
                        return;
                    }
                    beginPlacement(verifiedProfile.uuid(), verifiedProfile.username());
                }));
    }

    private void beginPlacement(UUID uuid, String username) {
        identity = new PlayerIdentity(uuid, owner.allocateConnectionId());
        view = new PlayerView(identity, username, Optional.empty());
        placementInProgress = true;
        frontend.config().setAutoRead(false);
        CompletionStage<Optional<PlacementDecision>> stage = owner.placement().apply(view);
        stage.whenComplete((decision, failure) -> frontend.eventLoop().execute(() -> {
            if (closed.get()) return;
            if (failure != null || decision == null) {
                closePair();
                return;
            }
            selectBackend(decision);
        }));
    }

    private boolean statusRequest;

    private void sendStatus() {
        String json = "{\"version\":{\"name\":\"1.7.10\",\"protocol\":5},\"players\":{\"max\":0,\"online\":0,\"sample\":[]},\"description\":{\"text\":\"StrataProxy\"}}";
        ByteBuf response = frontend.alloc().buffer();
        ProtocolVarInt.write(response, 0);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ProtocolVarInt.write(response, bytes.length);
        response.writeBytes(bytes);
        frontend.writeAndFlush(response);
    }

    private void selectBackend(Optional<PlacementDecision> decision) {
        BackendView backendView;
        if (decision.isPresent()) {
            if (!(decision.get() instanceof PlacementDecision.Select selectedDecision)) {
                closePair();
                return;
            }
            backendView = owner.catalog().snapshot().stream()
                    .filter(candidate -> candidate.handle().id().value().equals(selectedDecision.backendName()))
                    .findFirst().orElse(null);
        } else {
            backendView = owner.catalog().snapshot().stream().filter(candidate -> candidate.availableUnits() > 0).findFirst().orElse(null);
        }
        if (backendView == null) {
            closePair();
            return;
        }
        reservation = owner.catalog().reserve(backendView.handle(), 1).orElse(null);
        if (reservation == null) {
            closePair();
            return;
        }
        selected = backendView;
        if (!"tcp".equalsIgnoreCase(selected.address().getScheme()) || selected.address().getHost() == null || selected.address().getPort() < 1) {
            closePair();
            return;
        }
        Bootstrap bootstrap = new Bootstrap().group(frontend.eventLoop()).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new io.netty.channel.ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        installCodecs(channel.pipeline());
                        channel.pipeline().addLast("initial-session", Session.this);
                    }
                });
        ChannelFuture connect = bootstrap.connect(new InetSocketAddress(selected.address().getHost(), selected.address().getPort()));
        connect.addListener(future -> {
            if (!future.isSuccess()) {
                closePair();
                return;
            }
            backend = connect.channel();
            ByteBuf handshakeBody = owner.onlineMode()
                    ? BungeeLegacyForwarding.encode(frontend.alloc(), handshake,
                            (InetSocketAddress) frontend.remoteAddress(), verifiedProfile)
                    : handshake.encode(frontend.alloc(), ProtocolProfile.minecraft1710());
            ByteBuf loginBody = new LoginStart(view.username()).encode(frontend.alloc(), ProtocolProfile.minecraft1710());
            backend.write(handshakeBody);
            backend.writeAndFlush(loginBody);
        });
    }

    private static void installCodecs(ChannelPipeline pipeline) {
        pipeline.addLast("minecraft-frame-decoder", new dev.strataproxy.core.protocol.MinecraftFrameDecoder(ProtocolProfile.minecraft1710()));
        pipeline.addLast("minecraft-frame-encoder", new SessionFrameEncoder());
    }

    private void receiveBackend(ByteBuf packet) {
        ByteBuf input = packet.duplicate();
        int id = ProtocolVarInt.read(input);
        if (id == 1) { // Encryption Request means backend is not in offline mode.
            closePair();
            return;
        }
        if (id == 0) { // Login Disconnect.
            frontend.writeAndFlush(packet.copy()).addListener(ignored -> closePair());
            return;
        }
        if (id != 2 || published) {
            closePair();
            return;
        }
        String backendUuid = readString(input, 36);
        String backendName = readString(input, 16);
        if (input.isReadable() || !backendName.equals(view.username())) {
            closePair();
            return;
        }
        UUID expected = owner.onlineMode() ? verifiedProfile.uuid()
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + backendName).getBytes(StandardCharsets.UTF_8));
        try {
            if (!expected.equals(UUID.fromString(backendUuid))) {
                closePair();
                return;
            }
        } catch (IllegalArgumentException malformedUuid) {
            closePair();
            return;
        }
        if (reservation == null || !reservation.commit()) {
            closePair();
            return;
        }
        published = true;
        owner.onlineMap().put(identity, new PlayerView(identity, view.username(), selected.handle().id().value()));
        owner.sessions().put(identity, this);
        relayStarting = true;
        frontend.config().setAutoRead(false);
        backend.config().setAutoRead(false);
        frontend.writeAndFlush(packet.copy()).addListener(write -> {
            if (!write.isSuccess()) { closePair(); return; }
            frontend.eventLoop().execute(this::startRelay);
        });
    }

    private static String readString(ByteBuf input, int maxChars) {
        int length = ProtocolVarInt.read(input);
        if (length < 0 || length > maxChars * 4 || input.readableBytes() < length) throw new IllegalArgumentException("invalid login string");
        String value = input.toString(input.readerIndex(), length, StandardCharsets.UTF_8);
        input.skipBytes(length);
        if (value.length() > maxChars) throw new IllegalArgumentException("login string too long");
        return value;
    }

    private void startRelay() {
        Channel target = backend;
        if (closed.get() || target == null || !frontend.isActive() || !target.isActive()) { closePair(); return; }
        try {
            CompletableFuture<Void> buffered = flushTransitionFrames();
            buffered.whenComplete((ignored, bufferFailure) -> {
                if (bufferFailure != null) { closePair(); return; }
                attachRawRelay();
            });
        } catch (RuntimeException failure) {
            closePair();
        }
    }

    private void attachRawRelay() {
        Channel target = backend;
        if (closed.get() || target == null || !frontend.isActive() || !target.isActive()) { closePair(); return; }
        try {
            var link = dev.strataproxy.core.relay.RawRelay.attach(frontend, target);
            link.ready().whenComplete((ignored, failure) -> {
                if (failure != null) { closePair(); return; }
                CompletableFuture<Void> frontRemoved = removeHandshakeCodecs(frontend);
                CompletableFuture<Void> backRemoved = removeHandshakeCodecs(target);
                CompletableFuture.allOf(frontRemoved, backRemoved).whenComplete((removed, removeFailure) -> {
                    if (removeFailure != null) closePair();
                    else link.start();
                });
            });
        } catch (RuntimeException failure) {
            closePair();
        }
    }

    private void bufferTransitionFrame(boolean fromFrontend, ByteBuf packet) {
        int bytes = packet.readableBytes();
        if (transitionBufferBytes + bytes > MAX_TRANSITION_BUFFER_BYTES) {
            closePair();
            return;
        }
        transitionBuffer.addLast(new PendingFrame(fromFrontend, packet.retainedDuplicate()));
        transitionBufferBytes += bytes;
    }

    private CompletableFuture<Void> flushTransitionFrames() {
        if (transitionBuffer.isEmpty()) return CompletableFuture.completedFuture(null);
        ArrayList<CompletableFuture<Void>> writes = new ArrayList<>(transitionBuffer.size());
        PendingFrame pending;
        while ((pending = transitionBuffer.pollFirst()) != null) {
            transitionBufferBytes -= pending.payload.readableBytes();
            Channel target = pending.fromFrontend ? backend : frontend;
            if (target == null || !target.isActive()) {
                pending.payload.release();
                return CompletableFuture.failedFuture(new IllegalStateException("transition peer closed"));
            }
            CompletableFuture<Void> write = new CompletableFuture<>();
            writes.add(write);
            target.writeAndFlush(pending.payload).addListener(future -> {
                if (future.isSuccess()) write.complete(null);
                else write.completeExceptionally(future.cause());
            });
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> removeHandshakeCodecs(Channel channel) {
        CompletableFuture<Void> removed = new CompletableFuture<>();
        channel.eventLoop().execute(() -> {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get("initial-session") != null) pipeline.remove("initial-session");
                if (pipeline.get("minecraft-frame-decoder") != null) pipeline.remove("minecraft-frame-decoder");
                if (pipeline.get("encrypted-frame-decoder") != null) pipeline.remove("encrypted-frame-decoder");
                if (pipeline.get("minecraft-frame-encoder") != null) pipeline.remove("minecraft-frame-encoder");
                pipeline.addLast("session-lifecycle", new ChannelInboundHandlerAdapter() {
                    @Override public void channelInactive(ChannelHandlerContext ctx) {
                        closePair();
                        ctx.fireChannelInactive();
                    }

                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        closePair();
                    }
                });
                removed.complete(null);
            } catch (Throwable failure) {
                removed.completeExceptionally(failure);
            }
        });
        return removed;
    }

    void closePair() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = () -> {
            frontend.close();
            Channel upstream = backend;
            if (upstream != null) upstream.close();
            if (reservation != null) reservation.close();
            owner.allSessions().remove(this);
            PendingFrame pending;
            while ((pending = transitionBuffer.pollFirst()) != null) pending.payload.release();
            transitionBufferBytes = 0;
            if (published && selected != null) {
                owner.onlineMap().remove(identity);
                owner.sessions().remove(identity, this);
            }
        };
        if (frontend.eventLoop().inEventLoop()) cleanup.run();
        else frontend.eventLoop().execute(cleanup);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        closePair();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        closePair();
    }

    private record PendingFrame(boolean fromFrontend, ByteBuf payload) { }
}
