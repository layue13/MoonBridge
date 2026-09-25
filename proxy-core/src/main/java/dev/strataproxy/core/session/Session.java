package dev.strataproxy.core.session;

import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.PlayerIdentity;
import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.TransferResult;
import dev.strataproxy.api.TransferStatus;
import dev.strataproxy.core.auth.AuthenticatedEncryption;
import dev.strataproxy.core.auth.MinecraftCipherDecoder;
import dev.strataproxy.core.auth.MinecraftCipherEncoder;
import dev.strataproxy.core.auth.MinecraftEncryptionRequest;
import dev.strataproxy.core.auth.MinecraftEncryptionResponse;
import dev.strataproxy.core.auth.OnlineModeCrypto;
import dev.strataproxy.core.auth.VerifiedProfile;
import dev.strataproxy.core.backend.BackendView;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.CapacityReservation;
import dev.strataproxy.core.forwarding.BungeeLegacyForwarding;
import dev.strataproxy.core.protocol.LoginStart;
import dev.strataproxy.core.protocol.Minecraft1710EntityIds;
import dev.strataproxy.core.protocol.Minecraft1710PlayPackets;
import dev.strataproxy.core.protocol.MinecraftLoginSuccess;
import dev.strataproxy.core.protocol.MinecraftLoginDisconnect;
import dev.strataproxy.core.protocol.MinecraftHandshake;
import dev.strataproxy.core.protocol.ProtocolProfile;
import dev.strataproxy.core.protocol.ProtocolVarInt;
import dev.strataproxy.core.relay.RawRelay;
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
import java.net.URI;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mutable session control state is confined to the frontend event loop. */
@io.netty.channel.ChannelHandler.Sharable
final class Session extends ChannelInboundHandlerAdapter {
    private static final int MAX_TRANSITION_BUFFER_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes();
    private static final Duration FORGE_TRANSFER_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);
    private final ProxySessionListener owner;
    private final Channel frontend;
    private volatile Channel backend;
    private MinecraftHandshake handshake;
    private LoginStart loginStart;
    private MinecraftEncryptionRequest encryptionRequest;
    private VerifiedProfile verifiedProfile;
    private CompletableFuture<Optional<VerifiedProfile>> verification;
    private boolean verifyingIdentity;
    private PlayerIdentity identity;
    private boolean identityClaimed;
    private volatile PlayerView view;
    private BackendView selected;
    private CapacityReservation reservation;
    private boolean placementInProgress;
    private boolean loginDisconnectSent;
    private volatile boolean published;
    private boolean relayStarting;
    private int transitionBufferBytes;
    private final ArrayDeque<PendingFrame> transitionBuffer = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ScheduledFuture<?> initialLoginDeadline;
    private ScheduledFuture<?> initialPlayDeadline;
    private PlayObservation playObservation;
    private KeepAliveBridge.State keepAlives;
    private RawRelay.Link relay;
    private Integer clientEntityId;
    private TransferFrameHandler.State frameState;
    private PendingTransfer pendingTransfer;
    private TransferAttempt transfer;

    Session(ProxySessionListener owner, Channel frontend) {
        this.owner = owner;
        this.frontend = frontend;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        if (ctx.channel() == frontend) {
            resetLoginDeadline(owner.loginStageTimeout());
        }
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf packet)) {
            ReferenceCountUtil.release(message);
            closePair();
            return;
        }
        try {
            if (closed.get()) return;
            ByteBuf body = packet.duplicate();
            int frameLength = ProtocolVarInt.read(body);
            if (frameLength < 1 || frameLength != body.readableBytes()) {
                throw new IllegalArgumentException("invalid session frame");
            }
            if (relayStarting) {
                bufferTransitionFrame(ctx.channel() == frontend, body);
                return;
            }
            if (ctx.channel() == frontend) receiveFrontend(body);
            else receiveBackend(body);
        } catch (RuntimeException failure) {
            closePair();
        } finally {
            packet.release();
        }
    }

    private void receiveFrontend(ByteBuf packet) {
        if (handshake == null) {
            handshake = MinecraftHandshake.decode(packet, ProtocolProfile.minecraft1710());
            if (handshake.nextState() == MinecraftHandshake.NextState.LOGIN) {
                int separator = handshake.serverAddress().indexOf('\0');
                if (separator >= 0) {
                    handshake = new MinecraftHandshake(handshake.protocolVersion(),
                            handshake.serverAddress().substring(0, separator), handshake.serverPort(), handshake.nextState());
                }
            }
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
        ChannelPipeline pipeline = frontend.pipeline();
        try {
            pipeline.addAfter("minecraft-frame-decoder", "minecraft-cipher-decoder", new MinecraftCipherDecoder(secret));
            pipeline.addAfter("minecraft-cipher-decoder", "encrypted-frame-decoder",
                    new dev.strataproxy.core.protocol.MinecraftFrameDecoder(ProtocolProfile.minecraft1710(), true,
                            ProtocolProfile.MAX_LOGIN_FRAME_BYTES));
            pipeline.addBefore("minecraft-frame-encoder", "minecraft-cipher-encoder", new MinecraftCipherEncoder(secret));
            pipeline.remove("minecraft-frame-decoder");
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
        observeWaitingClient();
        String clientIp = ((InetSocketAddress) frontend.remoteAddress()).getAddress().getHostAddress();
        verification = owner.verifier().verify(loginStart.username(), serverHash, clientIp).toCompletableFuture();
        verification.whenComplete((profile, failure) -> frontend.eventLoop().execute(() -> {
                    verification = null;
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
        if (!owner.claimIdentity(uuid, this)) {
            closePair();
            return;
        }
        identityClaimed = true;
        view = new PlayerView(identity, username, Optional.empty());
        placementInProgress = true;
        observeWaitingClient();
        resetLoginDeadline(owner.placementTimeout().plusSeconds(1));
        CompletionStage<Optional<PlacementDecision>> stage = owner.placement().apply(view);
        stage.whenComplete((decision, failure) -> frontend.eventLoop().execute(() -> {
            if (closed.get()) return;
            if (failure != null || decision == null) {
                disconnectLogin("Could not select a server. Please try again.");
                return;
            }
            selectBackend(decision);
        }));
    }

    private boolean statusRequest;

    private void observeWaitingClient() {
        ChannelPipeline pipeline = frontend.pipeline();
        if (pipeline.get("login-wait-guard") == null) {
            pipeline.addFirst("login-wait-guard", new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                    ReferenceCountUtil.release(message);
                    closePair();
                }
            });
        }
        frontend.config().setAutoRead(true);
    }

    private void sendStatus() {
        int online = owner.onlineCount();
        long declaredCapacity = 0;
        for (BackendView backendView : owner.catalog().snapshot()) {
            declaredCapacity = Math.min(Integer.MAX_VALUE, declaredCapacity + backendView.capacity());
        }
        int max = (int) Math.max(online, declaredCapacity);
        String json = "{\"version\":{\"name\":\"1.7.10\",\"protocol\":5},\"players\":{\"max\":"
                + max + ",\"online\":" + online
                + ",\"sample\":[]},\"description\":{\"text\":\"StrataProxy\"}}";
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
            if (decision.get() instanceof PlacementDecision.Reject rejected) {
                disconnectLogin(rejected.reason());
                return;
            }
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
            disconnectLogin("No server is available.");
            return;
        }
        reservation = owner.catalog().reserve(backendView.handle(), 1).orElse(null);
        if (reservation == null) {
            disconnectLogin("The selected server is full or unavailable.");
            return;
        }
        selected = reservation.backendView();
        if (!"tcp".equalsIgnoreCase(selected.address().getScheme()) || selected.address().getHost() == null || selected.address().getPort() < 1) {
            closePair();
            return;
        }
        resetLoginDeadline(owner.loginStageTimeout());
        Bootstrap bootstrap = new Bootstrap().group(frontend.eventLoop()).channel(NioSocketChannel.class)
                .resolver(owner.backendResolver())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new io.netty.channel.ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        requireSessionEventLoop(channel);
                        installCodecs(channel.pipeline());
                        channel.pipeline().addLast("initial-session", Session.this);
                    }
                });
        ChannelFuture connect = bootstrap.connect(backendSocketAddress(selected.address()));
        backend = connect.channel();
        connect.addListener(future -> {
            if (closed.get()) {
                connect.channel().close();
                return;
            }
            if (!future.isSuccess()) {
                disconnectLogin("Could not connect to the selected server.");
                return;
            }
            ByteBuf handshakeBody = null;
            ByteBuf loginBody;
            try {
                handshakeBody = owner.onlineMode()
                        ? BungeeLegacyForwarding.encode(frontend.alloc(), handshake,
                                (InetSocketAddress) frontend.remoteAddress(), verifiedProfile)
                        : handshake.encode(frontend.alloc(), ProtocolProfile.minecraft1710());
                loginBody = new LoginStart(view.username()).encode(frontend.alloc(), ProtocolProfile.minecraft1710());
            } catch (RuntimeException failure) {
                ReferenceCountUtil.release(handshakeBody);
                disconnectLogin("Could not prepare backend login.");
                return;
            }
            backend.write(handshakeBody);
            backend.writeAndFlush(loginBody);
        });
    }

    private static void installCodecs(ChannelPipeline pipeline) {
        pipeline.addLast("minecraft-frame-decoder", new dev.strataproxy.core.protocol.MinecraftFrameDecoder(
                ProtocolProfile.minecraft1710(), true));
        pipeline.addLast("minecraft-frame-encoder", new SessionFrameEncoder());
    }

    private void disconnectLogin(String reason) {
        if (closed.get() || loginDisconnectSent) return;
        loginDisconnectSent = true;
        frontend.config().setAutoRead(false);
        if (!frontend.isActive()) { closePair(); return; }
        try {
            frontend.writeAndFlush(MinecraftLoginDisconnect.encode(frontend.alloc(), reason))
                    .addListener(ignored -> closePair());
        } catch (RuntimeException failure) {
            closePair();
        }
    }

    private void resetLoginDeadline(Duration timeout) {
        if (initialLoginDeadline != null) initialLoginDeadline.cancel(false);
        initialLoginDeadline = frontend.eventLoop().schedule(this::closePair,
                timeout.toNanos(), TimeUnit.NANOSECONDS);
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
        MinecraftLoginSuccess success = MinecraftLoginSuccess.decode(packet);
        if (!success.username().equals(view.username())) {
            closePair();
            return;
        }
        UUID expected = owner.onlineMode() ? verifiedProfile.uuid()
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + success.username()).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(success.playerId())) {
            closePair();
            return;
        }
        if (reservation == null || !reservation.commit()) {
            closePair();
            return;
        }
        allowFrontendPlayFrames();
        playObservation = new PlayObservation();
        keepAlives = new KeepAliveBridge.State();
        if (initialLoginDeadline != null) initialLoginDeadline.cancel(false);
        initialPlayDeadline = frontend.eventLoop().schedule(() -> {
            if (!closed.get() && !playObservation.ready().isDone()) closePair();
        }, owner.initialPlayTimeout().toNanos(), TimeUnit.NANOSECONDS);
        playObservation.ready().whenComplete((ignored, failure) -> {
            if (closed.get()) return;
            frontend.eventLoop().execute(() -> {
                initialPlayDeadline.cancel(false);
                if (failure == null) tryStartPendingTransfer();
            });
        });
        view = new PlayerView(identity, view.username(), selected.handle().id().value());
        published = true;
        owner.sessionPublished();
        relayStarting = true;
        frontend.config().setAutoRead(false);
        if (frontend.pipeline().get("login-wait-guard") != null) {
            frontend.pipeline().remove("login-wait-guard");
        }
        backend.config().setAutoRead(false);
        frontend.writeAndFlush(packet.copy()).addListener(write -> {
            if (!write.isSuccess()) { closePair(); return; }
            frontend.eventLoop().execute(this::startRelay);
        });
    }

    private void allowFrontendPlayFrames() {
        ChannelPipeline pipeline = frontend.pipeline();
        Object decoder = pipeline.get("encrypted-frame-decoder") != null
                ? pipeline.get("encrypted-frame-decoder") : pipeline.get("minecraft-frame-decoder");
        if (!(decoder instanceof dev.strataproxy.core.protocol.MinecraftFrameDecoder frames)) {
            throw new IllegalStateException("client frame decoder missing after login success");
        }
        frames.allowPlayFrames();
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
            var observation = playObservation;
            frontend.pipeline().addLast("keep-alive-bridge", new KeepAliveBridge(keepAlives, true, this::closePair));
            target.pipeline().addLast("keep-alive-bridge", new KeepAliveBridge(keepAlives, false, this::closePair));
            var link = RawRelay.attach(frontend, target,
                    bytes -> observation.observeFrame(false, bytes),
                    bytes -> observation.observeFrame(true, bytes));
            observation.ready().whenComplete((ignored, failure) -> link.stopObserving());
            link.ready().whenComplete((ignored, failure) -> {
                if (failure != null) { closePair(); return; }
                CompletableFuture<Void> frontRemoved = removeHandshakeCodecs(frontend);
                CompletableFuture<Void> backRemoved = removeHandshakeCodecs(target);
                CompletableFuture.allOf(frontRemoved, backRemoved).whenComplete((removed, removeFailure) -> {
                    if (removeFailure != null) closePair();
                    else {
                        relay = link;
                        link.start();
                        tryStartPendingTransfer();
                    }
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
            ByteBuf payload = pending.payload;
            try {
                if (playObservation != null) {
                    playObservation.observePacket(!pending.fromFrontend, payload);
                }
            } catch (RuntimeException malformed) {
                payload.release();
                return CompletableFuture.failedFuture(malformed);
            }
            Channel target = pending.fromFrontend ? backend : frontend;
            if (target == null || !target.isActive()) {
                payload.release();
                return CompletableFuture.failedFuture(new IllegalStateException("transition peer closed"));
            }
            try {
                ByteBuf mapped = keepAlives.body(target.alloc(), payload, pending.fromFrontend);
                if (mapped != payload) {
                    payload.release();
                    payload = mapped;
                }
            } catch (RuntimeException malformed) {
                payload.release();
                return CompletableFuture.failedFuture(malformed);
            }
            if (payload == null) continue;
            CompletableFuture<Void> write = new CompletableFuture<>();
            writes.add(write);
            target.writeAndFlush(payload).addListener(future -> {
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
                if (pipeline.get("transfer-candidate") != null) pipeline.remove("transfer-candidate");
                if (pipeline.get("minecraft-frame-encoder") != null) pipeline.remove("minecraft-frame-encoder");
                if (pipeline.get("session-lifecycle") == null) pipeline.addLast("session-lifecycle", new ChannelInboundHandlerAdapter() {
                    @Override public void channelInactive(ChannelHandlerContext ctx) {
                        if (ctx.channel() == frontend || ctx.channel() == backend) closePair();
                        ctx.fireChannelInactive();
                    }

                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        if (ctx.channel() == frontend || ctx.channel() == backend) closePair();
                    }
                });
                removed.complete(null);
            } catch (Throwable failure) {
                removed.completeExceptionally(failure);
            }
        });
        return removed;
    }

    CompletionStage<TransferResult> transferTo(String backendName) {
        CompletableFuture<TransferResult> result = new CompletableFuture<>();
        Runnable command = () -> beginTransfer(backendName, result);
        if (frontend.eventLoop().inEventLoop()) command.run();
        else frontend.eventLoop().execute(command);
        return result;
    }

    private void beginTransfer(String backendName, CompletableFuture<TransferResult> result) {
        if (closed.get() || !published) {
            result.complete(TransferResult.of(TransferStatus.PLAYER_NOT_CONNECTED));
            return;
        }
        if (transfer != null || pendingTransfer != null) {
            result.complete(TransferResult.failed("a backend transfer is already in progress"));
            return;
        }
        if (selected.handle().id().value().equals(backendName)) {
            BackendView current = owner.catalog().find(selected.handle().id()).orElse(null);
            if (current != null && current.address().equals(selected.address())) {
                result.complete(TransferResult.of(TransferStatus.NETWORK_READY));
                return;
            }
        }
        if (playObservation.ready().isCompletedExceptionally()) {
            result.complete(TransferResult.failed("backend login or Forge negotiation failed"));
            return;
        }
        if (relay == null || !playObservation.ready().isDone()) {
            PendingTransfer waiting = new PendingTransfer(backendName, result);
            pendingTransfer = waiting;
            waiting.deadline = frontend.eventLoop().schedule(() -> {
                if (pendingTransfer == waiting) {
                    pendingTransfer = null;
                    result.complete(TransferResult.failed("backend login or Forge negotiation timed out"));
                }
            }, 15, TimeUnit.SECONDS);
            return;
        }
        BackendView target;
        try {
            target = owner.catalog().find(new BackendId(backendName)).orElse(null);
        } catch (IllegalArgumentException invalidName) {
            target = null;
        }
        if (target == null || !"tcp".equalsIgnoreCase(target.address().getScheme())
                || target.address().getHost() == null || target.address().getPort() < 1) {
            result.complete(TransferResult.of(TransferStatus.SERVER_UNAVAILABLE));
            return;
        }
        CapacityReservation claim = owner.catalog().reserve(target.handle(), 1).orElse(null);
        if (claim == null) {
            result.complete(TransferResult.of(TransferStatus.SERVER_UNAVAILABLE));
            return;
        }
        TransferAttempt attempt = new TransferAttempt(claim.backendView(), claim, result, relay);
        transfer = attempt;
        attempt.candidate = new TransferCandidate(identity.playerId(), view.username(), new TransferCandidate.Listener() {
            @Override public void ready(TransferCandidate candidate) { candidateReady(attempt); }
            @Override public void failed(TransferCandidate candidate, String reason) { failTransfer(attempt, reason); }
        });
        try {
            Bootstrap bootstrap = new Bootstrap().group(frontend.eventLoop()).channel(NioSocketChannel.class)
                    .resolver(owner.backendResolver())
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new io.netty.channel.ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            requireSessionEventLoop(channel);
                            channel.pipeline().addLast("minecraft-frame-decoder",
                                    new dev.strataproxy.core.protocol.MinecraftFrameDecoder(
                                            ProtocolProfile.minecraft1710(), true));
                            channel.pipeline().addLast("minecraft-frame-encoder", new SessionFrameEncoder());
                            channel.pipeline().addLast("transfer-candidate", attempt.candidate);
                        }
                    });
            ChannelFuture connect = bootstrap.connect(backendSocketAddress(attempt.target.address()));
            attempt.channel = connect.channel();
            connect.addListener(future -> {
                if (attempt.finished || closed.get()) { connect.channel().close(); return; }
                if (!future.isSuccess()) { failTransfer(attempt, "could not connect to replacement backend"); return; }
                writeBackendLogin(connect.channel());
            });
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not start replacement backend connection");
        }
    }

    private void tryStartPendingTransfer() {
        PendingTransfer waiting = pendingTransfer;
        if (waiting == null || relay == null || !playObservation.ready().isDone()) return;
        pendingTransfer = null;
        waiting.deadline.cancel(false);
        beginTransfer(waiting.backendName, waiting.result);
    }

    private void writeBackendLogin(Channel channel) {
        ByteBuf handshakeBody = owner.onlineMode()
                ? BungeeLegacyForwarding.encode(frontend.alloc(), handshake,
                        (InetSocketAddress) frontend.remoteAddress(), verifiedProfile)
                : handshake.encode(frontend.alloc(), ProtocolProfile.minecraft1710());
        ByteBuf loginBody = new LoginStart(view.username()).encode(frontend.alloc(), ProtocolProfile.minecraft1710());
        channel.write(handshakeBody);
        channel.writeAndFlush(loginBody).addListener(write -> {
            if (!write.isSuccess() && transfer != null && transfer.channel == channel) {
                failTransfer(transfer, "could not write replacement backend login");
            }
        });
    }

    private static InetSocketAddress backendSocketAddress(URI address) {
        String host = address.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return InetSocketAddress.createUnresolved(host, address.getPort());
    }

    private void requireSessionEventLoop(Channel channel) {
        if (channel.eventLoop() != frontend.eventLoop()) {
            throw new IllegalStateException("backend channel must share the player session event loop");
        }
    }

    private void candidateReady(TransferAttempt attempt) {
        if (transfer != attempt || attempt.finished || closed.get()) return;
        attempt.cutoverDeadline = frontend.eventLoop().schedule(() -> {
            if (transfer != attempt || attempt.result.isDone()) return;
            attempt.result.complete(TransferResult.failed("replacement backend cutover timed out"));
            closePair();
        }, owner.transferCutoverTimeout().toNanos(), TimeUnit.NANOSECONDS);
        try {
            attempt.clientBuffer = installTransferBuffer(frontend, "transfer-client-buffer");
            attempt.oldBackendBuffer = installTransferBuffer(backend, "transfer-old-backend-buffer");
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not buffer frames for transfer");
            closePair();
            return;
        }
        attempt.pauseInProgress = true;
        CompletionStage<Void> pause = attempt.oldRelay.pause();
        frontend.eventLoop().execute(attempt.clientBuffer::readUntilRemoved);
        backend.eventLoop().execute(attempt.oldBackendBuffer::readUntilRemoved);
        pause.whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
            attempt.pauseInProgress = false;
            if (failure != null) {
                if (!attempt.finished) failTransfer(attempt, "old backend stopped during transfer");
                closePair();
                return;
            }
            attempt.paused = true;
            if (attempt.finished || closed.get()) {
                resumeAfterFailedTransfer(attempt);
                return;
            }
            if (clientHasPartialFrame()) {
                failTransfer(attempt, "client packet was incomplete at the transfer boundary");
                return;
            }
            if (!attempt.channel.isActive() || !attempt.claim.commit()) {
                failTransfer(attempt, "replacement backend became unavailable");
                return;
            }
            attempt.oldRelay.detach().whenComplete((removed, detachFailure) ->
                    frontend.eventLoop().execute(() -> {
                        if (detachFailure != null) {
                            boolean oldRelayStillAttached = frontend.pipeline().get("raw-relay") != null
                                    && backend.pipeline().get("raw-relay") != null;
                            failTransfer(attempt, "could not detach old backend relay");
                            if (!oldRelayStillAttached) closePair();
                            return;
                        }
                        if (closed.get() || !attempt.channel.isActive()) {
                            failTransfer(attempt, "replacement backend closed during transfer");
                            closePair();
                            return;
                        }
                        attempt.detached = true;
                        activateCandidate(attempt);
                    }));
        }));
    }

    private TransferFrameBuffer installTransferBuffer(Channel channel, String name) {
        ChannelPipeline pipeline = channel.pipeline();
        String decoder = pipeline.get("encrypted-frame-decoder") != null
                ? "encrypted-frame-decoder" : "minecraft-frame-decoder";
        var buffer = new TransferFrameBuffer(this::closePair);
        pipeline.addAfter(decoder, name, buffer);
        return buffer;
    }

    private boolean clientHasPartialFrame() {
        ChannelPipeline pipeline = frontend.pipeline();
        var decoder = pipeline.get("minecraft-frame-decoder");
        if (decoder == null) decoder = pipeline.get("encrypted-frame-decoder");
        if (!(decoder instanceof dev.strataproxy.core.protocol.MinecraftFrameDecoder frameDecoder)) {
            throw new IllegalStateException("client frame decoder missing during transfer");
        }
        return frameDecoder.hasPartialFrame();
    }

    private void activateCandidate(TransferAttempt attempt) {
        try {
            attempt.candidate.handOff();
        } catch (RuntimeException invalid) {
            failTransfer(attempt, "replacement backend was not ready");
            closePair();
            return;
        }
        removeHandshakeCodecs(attempt.channel).whenComplete((ignored, failure) ->
                frontend.eventLoop().execute(() -> {
                    if (failure != null || closed.get() || !attempt.channel.isActive()) {
                        failTransfer(attempt, "replacement backend pipeline failed");
                        closePair();
                        return;
                    }
                    installCandidateRelay(attempt);
                }));
    }

    private void installCandidateRelay(TransferAttempt attempt) {
        PlayObservation nextObservation = attempt.candidate.observation();
        RawRelay.Link next;
        try {
            if (clientEntityId == null) clientEntityId = playObservation.entityId().orElseThrow();
            attempt.frameState = new TransferFrameHandler.State(frontend, attempt.channel, nextObservation,
                    clientEntityId, attempt.candidate.joinGame(), this::closePair);
            attempt.channel.pipeline().addLast("keep-alive-bridge",
                    new KeepAliveBridge(keepAlives, false, this::closePair));
            installTransferFrameHandlers(attempt.frameState, attempt.channel);
            next = RawRelay.attach(frontend, attempt.channel,
                    bytes -> nextObservation.observeFrame(false, bytes),
                    bytes -> nextObservation.observeFrame(true, bytes));
            RawRelay.Link observedLink = next;
            nextObservation.ready().whenComplete((ignored, failure) -> observedLink.stopObserving());
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not attach replacement relay");
            closePair();
            return;
        }
        next.ready().whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
            if (failure != null || closed.get()) {
                failTransfer(attempt, "replacement relay was not ready");
                closePair();
                return;
            }
            ByteBuf opening;
            try {
                keepAlives.switchBackend();
                opening = transferOpening(attempt);
            } catch (RuntimeException malformed) {
                failTransfer(attempt, "could not prepare replacement world transition");
                closePair();
                return;
            }
            frontend.writeAndFlush(opening).addListener(write -> frontend.eventLoop().execute(() -> {
                if (!write.isSuccess() || closed.get() || !attempt.channel.isActive()) {
                    failTransfer(attempt, "could not send replacement world transition");
                    closePair();
                    return;
                }
                Channel oldBackend = backend;
                CapacityReservation oldReservation = reservation;
                PlayObservation oldObservation = playObservation;
                TransferFrameHandler.State oldFrameState = frameState;
                backend = attempt.channel;
                reservation = attempt.claim;
                selected = attempt.target;
                playObservation = nextObservation;
                relay = next;
                frameState = attempt.frameState;
                view = new PlayerView(identity, view.username(), selected.handle().id().value());
                oldBackend.close();
                oldReservation.close();
                oldObservation.close();
                if (oldFrameState != null) oldFrameState.close();
                try {
                    attempt.clientBuffer.drainAndRemove();
                } catch (RuntimeException replayFailure) {
                    closePair();
                    return;
                }
                if (closed.get()) return;
                next.start();
                if (closed.get()) return;
                if (nextObservation.forgeSeen()) {
                    attempt.cutoverDeadline.cancel(false);
                    attempt.cutoverDeadline = frontend.eventLoop().schedule(() -> {
                        if (transfer != attempt || attempt.finished || closed.get()) return;
                        failTransfer(attempt, "replacement Forge handshake timed out");
                        closePair();
                    }, FORGE_TRANSFER_HANDSHAKE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
                    CompletableFuture.allOf(nextObservation.ready(), attempt.frameState.worldReady())
                            .whenComplete((negotiated, negotiationFailure) -> frontend.eventLoop().execute(() -> {
                                if (transfer != attempt || attempt.finished || closed.get()) return;
                                if (negotiationFailure != null || !attempt.channel.isActive()) {
                                    failTransfer(attempt, "replacement Forge handshake failed");
                                    closePair();
                                } else finishTransfer(attempt);
                            }));
                } else finishTransfer(attempt);
            }));
        }));
    }

    private void finishTransfer(TransferAttempt attempt) {
        if (transfer != attempt || attempt.finished || closed.get()) return;
        transfer = null;
        attempt.finished = true;
        attempt.cutoverDeadline.cancel(false);
        attempt.result.complete(TransferResult.of(TransferStatus.NETWORK_READY));
    }

    private void installTransferFrameHandlers(TransferFrameHandler.State state, Channel target) {
        ChannelPipeline clientPipeline = frontend.pipeline();
        if (clientPipeline.get("transfer-frame-handler") != null) clientPipeline.remove("transfer-frame-handler");
        if (clientPipeline.get("minecraft-frame-decoder") == null
                && clientPipeline.get("encrypted-frame-decoder") == null) {
            throw new IllegalStateException("client frame decoder missing during transfer");
        }
        clientPipeline.addLast("transfer-frame-handler", new TransferFrameHandler(state, false));
        target.pipeline().addLast("transfer-frame-handler", new TransferFrameHandler(state, true));
    }

    private ByteBuf transferOpening(TransferAttempt attempt) {
        ByteBuf output = frontend.alloc().buffer();
        List<ByteBuf> queued = attempt.candidate.takeQueuedPackets();
        try {
            if (attempt.candidate.observation().forgeSeen()) {
                ByteBuf reset = Minecraft1710PlayPackets.forgeReset(frontend.alloc());
                try { output.writeBytes(reset); } finally { reset.release(); }
            }
            if (attempt.candidate.joinGame() != null) {
                int targetDimension = attempt.candidate.observation().dimension()
                        .orElse(attempt.candidate.joinGame().dimension());
                ByteBuf respawns = Minecraft1710PlayPackets.respawnSequence(frontend.alloc(),
                        attempt.candidate.joinGame(), targetDimension);
                try { output.writeBytes(respawns); } finally { respawns.release(); }
            }
            for (ByteBuf packet : queued) {
                ByteBuf mapped = keepAlives.body(frontend.alloc(), packet, false);
                if (mapped == null) continue;
                try {
                    ByteBuf frame = Minecraft1710PlayPackets.frame(frontend.alloc(), mapped);
                    try {
                        ByteBuf outgoing = attempt.candidate.joinGame() == null ? frame
                                : Minecraft1710EntityIds.rewrite(frontend.alloc(), frame, true,
                                        attempt.candidate.joinGame().entityId(), clientEntityId);
                        try { output.writeBytes(outgoing); }
                        finally { if (outgoing != frame) outgoing.release(); }
                    } finally { frame.release(); }
                } finally {
                    if (mapped != packet) mapped.release();
                }
            }
            return output;
        } catch (RuntimeException failure) {
            output.release();
            throw failure;
        } finally {
            queued.forEach(ByteBuf::release);
        }
    }

    private void failTransfer(TransferAttempt attempt, String reason) {
        if (attempt.finished) return;
        attempt.finished = true;
        attempt.failureReason = reason;
        if (attempt.channel != null) attempt.channel.close();
        if (attempt.candidate != null) attempt.candidate.close();
        if (attempt.frameState != null) attempt.frameState.close();
        attempt.claim.close();
        if (!attempt.pauseInProgress) resumeAfterFailedTransfer(attempt);
    }

    private void resumeAfterFailedTransfer(TransferAttempt attempt) {
        if (attempt.paused && !attempt.detached && !closed.get()) {
            attempt.oldRelay.resume().whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
                if (failure != null) closePair();
                completeFailedTransfer(attempt);
            }));
        } else {
            completeFailedTransfer(attempt);
        }
    }

    private void completeFailedTransfer(TransferAttempt attempt) {
        if (!attempt.detached && !closed.get()) {
            try {
                if (attempt.oldBackendBuffer != null) attempt.oldBackendBuffer.drainAndRemove();
                if (attempt.clientBuffer != null) attempt.clientBuffer.drainAndRemove();
            } catch (RuntimeException failure) {
                closePair();
            }
        }
        if (attempt.cutoverDeadline != null) attempt.cutoverDeadline.cancel(false);
        if (transfer == attempt) transfer = null;
        attempt.result.complete(TransferResult.failed(attempt.failureReason));
    }

    void closePair() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = () -> {
            if (initialLoginDeadline != null) initialLoginDeadline.cancel(false);
            if (initialPlayDeadline != null) initialPlayDeadline.cancel(false);
            if (verification != null) verification.cancel(true);
            frontend.close();
            Channel upstream = backend;
            if (upstream != null) upstream.close();
            if (reservation != null) reservation.close();
            if (playObservation != null) playObservation.close();
            if (frameState != null) frameState.close();
            PendingTransfer waiting = pendingTransfer;
            if (waiting != null) {
                pendingTransfer = null;
                waiting.deadline.cancel(false);
                waiting.result.complete(TransferResult.failed("player session closed during transfer"));
            }
            TransferAttempt activeTransfer = transfer;
            if (activeTransfer != null) {
                transfer = null;
                activeTransfer.finished = true;
                if (activeTransfer.cutoverDeadline != null) activeTransfer.cutoverDeadline.cancel(false);
                if (activeTransfer.channel != null) activeTransfer.channel.close();
                activeTransfer.claim.close();
                if (activeTransfer.candidate != null) activeTransfer.candidate.close();
                activeTransfer.result.complete(TransferResult.failed("player session closed during transfer"));
            }
            if (identityClaimed) owner.releaseIdentity(identity.playerId(), this);
            if (published) owner.sessionUnpublished();
            owner.allSessions().remove(this);
            PendingFrame pending;
            while ((pending = transitionBuffer.pollFirst()) != null) pending.payload.release();
            transitionBufferBytes = 0;
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

    Optional<PlayerView> onlineView() {
        if (!published || closed.get()) return Optional.empty();
        return Optional.of(view);
    }

    private static final class TransferAttempt {
        private final BackendView target;
        private final CapacityReservation claim;
        private final CompletableFuture<TransferResult> result;
        private final RawRelay.Link oldRelay;
        private TransferCandidate candidate;
        private TransferFrameHandler.State frameState;
        private TransferFrameBuffer clientBuffer;
        private TransferFrameBuffer oldBackendBuffer;
        private Channel channel;
        private boolean pauseInProgress;
        private boolean paused;
        private boolean detached;
        private boolean finished;
        private String failureReason;
        private ScheduledFuture<?> cutoverDeadline;

        private TransferAttempt(BackendView target, CapacityReservation claim,
                                CompletableFuture<TransferResult> result, RawRelay.Link oldRelay) {
            this.target = target;
            this.claim = claim;
            this.result = result;
            this.oldRelay = oldRelay;
        }
    }

    private static final class PendingTransfer {
        private final String backendName;
        private final CompletableFuture<TransferResult> result;
        private ScheduledFuture<?> deadline;

        private PendingTransfer(String backendName, CompletableFuture<TransferResult> result) {
            this.backendName = backendName;
            this.result = result;
        }
    }

    private record PendingFrame(boolean fromFrontend, ByteBuf payload) { }
}
