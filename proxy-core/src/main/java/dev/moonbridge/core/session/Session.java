package dev.moonbridge.core.session;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.ServerView;
import dev.moonbridge.api.event.TransferContext;
import dev.moonbridge.api.event.TransferPreparingEvent;
import dev.moonbridge.core.event.TransferPreparation;
import dev.moonbridge.core.event.PreparedTransfer;
import dev.moonbridge.api.event.PlayerAdmissionEvent;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.core.auth.AuthenticatedEncryption;
import dev.moonbridge.core.auth.MinecraftCipherDecoder;
import dev.moonbridge.core.auth.MinecraftCipherEncoder;
import dev.moonbridge.core.auth.MinecraftEncryptionRequest;
import dev.moonbridge.core.auth.MinecraftEncryptionResponse;
import dev.moonbridge.core.auth.OnlineModeCrypto;
import dev.moonbridge.core.auth.VerifiedProfile;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.forwarding.BungeeLegacyForwarding;
import dev.moonbridge.messaging.session.ForwardedSessionProof;
import dev.moonbridge.core.protocol.LoginStart;
import dev.moonbridge.core.protocol.Minecraft1710EntityIds;
import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.MinecraftLoginSuccess;
import dev.moonbridge.core.protocol.MinecraftLoginDisconnect;
import dev.moonbridge.core.protocol.MinecraftHandshake;
import dev.moonbridge.core.protocol.MinecraftFrameDecoder;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import dev.moonbridge.core.relay.RawRelay;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.List;
import net.kyori.adventure.text.Component;
import dev.moonbridge.core.protocol.MinecraftText;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Mutable session control state is confined to the frontend event loop. */
@ChannelHandler.Sharable
final class Session extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(Session.class);
    private static final int MAX_TRANSITION_BUFFER_BYTES = ProtocolProfile.minecraft1710().maxFrameBytes();
    private static final int MAX_TRANSITION_BUFFER_FRAMES = 1024;
    private static final int MAX_PENDING_MESSAGES = 64;
    private static final Duration FORGE_TRANSFER_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LOGIN_DISCONNECT_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private final ProxySessionListener owner;
    private final Channel frontend;
    private volatile Channel backend;
    private boolean backendConnected;
    private MinecraftHandshake handshake;
    private boolean statusRequest;
    private LoginStart loginStart;
    private MinecraftEncryptionRequest encryptionRequest;
    private VerifiedProfile verifiedProfile;
    private CompletableFuture<Optional<VerifiedProfile>> verification;
    private PlayerIdentity identity;
    private boolean identityClaimed;
    private volatile PlayerView view;
    private BackendView selected;
    private CompletableFuture<Optional<PlacementDecision>> placementRequest;
    private List<String> initialCandidates = List.of();
    private int initialCandidateIndex;
    private long initialRoutingDeadlineNanos;
    private CompletableFuture<AccessDecision> admissionRequest;
    private boolean loginDisconnectStarted;
    private volatile boolean published;
    private boolean relayStarting;
    private int transitionBufferBytes;
    private final ArrayDeque<PendingFrame> transitionBuffer = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger pendingMessages = new AtomicInteger();
    private boolean frontendPlayPhase;
    private volatile boolean disconnecting;
    private boolean disconnectPacketStarted;
    private boolean disconnectRelayDrained;
    private String pendingDisconnectReason;
    private TabCompletionBridge tabCompletion;
    private CompletableFuture<DisconnectResult> disconnectResult;
    private ScheduledFuture<?> disconnectDeadline;
    private final CommandRateLimiter commandLimiter = new CommandRateLimiter(System.nanoTime());
    private ScheduledFuture<?> initialLoginDeadline;
    private ScheduledFuture<?> initialPlayDeadline;
    private PlayObservation playObservation;
    private KeepAliveBridge.State keepAlives;
    private RawRelay.Link relay;
    private Integer clientEntityId;
    private boolean clientFmlAwaitingServerHello;
    private TransferFrameHandler.State frameState;
    private PendingTransfer pendingTransfer;
    private TransferAttempt transfer;

    Session(ProxySessionListener owner, Channel frontend) {
        this.owner = owner;
        this.frontend = frontend;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        if (ctx.channel() == frontend) {
            resetLoginDeadline(ctx.pipeline().get(ConnectionGate.class) == null ? owner.loginStageTimeout()
                    : owner.eventTimeout().plusSeconds(1));
        }
    }

    void connectionAccepted() { resetLoginDeadline(owner.loginStageTimeout()); }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (ctx.channel() != frontend && ctx.channel() != backend) {
            ReferenceCountUtil.release(message);
            return;
        }
        if (!(message instanceof ByteBuf packet)) {
            ReferenceCountUtil.release(message);
            closePair();
            return;
        }
        try {
            if (closed.get() || disconnecting) return;
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
            LOGGER.debug("Closing session after {} frame handling failed",
                    ctx.channel() == frontend ? "client" : "backend", failure);
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
        if (encryptionRequest != null) {
            receiveEncryptionResponse(packet);
            return;
        }
        if (loginStart != null) {
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
        beginLogin(uuid, loginStart.username());
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
        observeWaitingClient();
        String clientIp = ((InetSocketAddress) frontend.remoteAddress()).getAddress().getHostAddress();
        verification = owner.verifier().verify(loginStart.username(), serverHash, clientIp).toCompletableFuture();
        verification.whenComplete((profile, failure) -> frontend.eventLoop().execute(() -> {
                    verification = null;
                    if (closed.get() || disconnecting) return;
                    if (failure != null || profile == null || profile.isEmpty()) {
                        closePair();
                        return;
                    }
                    verifiedProfile = profile.get();
                    if (!verifiedProfile.username().equalsIgnoreCase(loginStart.username())) {
                        closePair();
                        return;
                    }
                    beginLogin(verifiedProfile.uuid(), verifiedProfile.username());
                }));
    }

    private void beginLogin(UUID uuid, String username) {
        identity = new PlayerIdentity(uuid, owner.allocateConnectionId());
        if (!owner.claimIdentity(uuid, this)) {
            closePair();
            return;
        }
        identityClaimed = true;
        view = new PlayerView(identity, username, Optional.empty());
        observeWaitingClient();
        resetLoginDeadline(owner.eventTimeout().plusSeconds(1));
        try {
            owner.preparePermissions(view).whenComplete((ignored, failure) -> {
                try {
                    frontend.eventLoop().execute(() -> {
                        if (closed.get() || disconnecting || loginDisconnectStarted) return;
                        if (failure != null) {
                            LOGGER.debug("Permission preparation failed for {}", view.username(), failure);
                            disconnectLogin("Could not load permissions. Please try again.");
                        } else {
                            beginPlayerAdmission();
                        }
                    });
                } catch (RejectedExecutionException shutdown) {
                    closePair();
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.debug("Could not prepare permissions for {}", view.username(), failure);
            disconnectLogin("Could not load permissions. Please try again.");
        }
    }

    private void beginPlayerAdmission() {
        if (!owner.hasSubscribers(PlayerAdmissionEvent.class)) {
            beginPlacement();
            return;
        }
        resetLoginDeadline(owner.eventTimeout().plusSeconds(1));
        try {
            CompletableFuture<AccessDecision> request = Objects.requireNonNull(owner.dispatchEvent(
                    new PlayerAdmissionEvent(view, (InetSocketAddress) frontend.remoteAddress(), verifiedProfile != null)),
                    "player admission stage").toCompletableFuture();
            admissionRequest = request;
            request.whenComplete((decision, failure) -> {
                try {
                    frontend.eventLoop().execute(() -> {
                        if (admissionRequest == request) admissionRequest = null;
                        if (closed.get() || loginDisconnectStarted) return;
                        if (failure != null || decision == null) {
                            LOGGER.debug("Player admission failed for {}", view.username(), failure);
                            disconnectLogin("Could not check login access. Please try again.");
                        } else if (decision instanceof AccessDecision.Denied denied) {
                            disconnectLogin(denied.reason());
                        } else {
                            beginPlacement();
                        }
                    });
                } catch (RejectedExecutionException shutdown) {
                    closePair();
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.debug("Could not start player admission for {}", view.username(), failure);
            disconnectLogin("Could not check login access. Please try again.");
        }
    }

    private void beginPlacement() {
        initialRoutingDeadlineNanos = System.nanoTime() + owner.placementTimeout().toNanos();
        resetLoginDeadline(owner.placementTimeout());
        CompletableFuture<Optional<PlacementDecision>> request = owner.placement().apply(view).toCompletableFuture();
        placementRequest = request;
        request.whenComplete((decision, failure) -> {
            if (closed.get() || disconnecting) return;
            try {
                frontend.eventLoop().execute(() -> {
                    if (placementRequest == request) placementRequest = null;
                    if (closed.get() || disconnecting) return;
                    if (failure != null || decision == null) {
                        disconnectLogin("Could not select a server. Please try again.");
                        return;
                    }
                    selectBackend(decision);
                });
            } catch (RejectedExecutionException shutdown) {
                closePair();
            }
        });
    }

    private void observeWaitingClient() {
        ChannelPipeline pipeline = frontend.pipeline();
        if (pipeline.get("login-wait-guard") == null) {
            pipeline.addFirst("login-wait-guard", new FrameTransformHandler(this::closePair) {
                @Override protected ByteBuf transform(ChannelHandlerContext ctx, ByteBuf frame) {
                    throw new IllegalStateException("client sent data while login was pending");
                }
            });
        }
        frontend.config().setAutoRead(true);
    }

    private void sendStatus() {
        frontend.writeAndFlush(owner.serverListStatus().encode(frontend.alloc(), owner.onlineCount()));
    }

    private void selectBackend(Optional<PlacementDecision> decision) {
        if (decision.isPresent()) {
            if (decision.get() instanceof PlacementDecision.Reject rejected) {
                disconnectLogin(rejected.reason());
                return;
            }
            if (!(decision.get() instanceof PlacementDecision.Select selectedDecision)) {
                closePair();
                return;
            }
            initialCandidates = selectedDecision.backendNames();
        } else {
            initialCandidates = owner.initialServers();
        }
        initialCandidateIndex = 0;
        if (initialCandidates.isEmpty()) {
            disconnectLogin("No entry servers are configured.");
            return;
        }
        connectNextInitialBackend();
    }

    /** Retries only before a backend has received any Minecraft handshake/login bytes. */
    private void connectNextInitialBackend() {
        if (closed.get() || disconnecting) return;
        while (initialCandidateIndex < initialCandidates.size()) {
            long remaining = initialRoutingDeadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                disconnectLogin("Initial server routing timed out.");
                return;
            }
            String name = initialCandidates.get(initialCandidateIndex++);
            BackendView target = owner.catalog().find(new BackendId(name)).orElse(null);
            if (target == null) continue;
            if (!SessionChannels.isTcpAddress(target.address())) continue;
            connectInitialBackend(target, remaining);
            return;
        }
        disconnectLogin("No entry server could be reached.");
    }

    private void connectInitialBackend(BackendView target, long remainingNanos) {
        // Do not attach the shared Session until the attempt has won. A failed
        // dial's inactive/exception callbacks must not close a later connection.
        Bootstrap bootstrap = SessionChannels.backendBootstrap(frontend, owner.transport(), owner.backendResolver(),
                (int) Math.max(1, Math.min(5000, TimeUnit.NANOSECONDS.toMillis(remainingNanos))), false,
                pipeline -> { });
        ChannelFuture connect;
        try {
            connect = bootstrap.connect(SessionChannels.socketAddress(target.address()));
        } catch (RuntimeException failure) {
            LOGGER.debug("Initial backend dial could not start for {} to {}", view.username(), target.address(), failure);
            connectNextInitialBackend();
            return;
        }
        backend = connect.channel();
        connect.addListener(ignored -> finishInitialConnection(connect, target));
    }

    private void finishInitialConnection(ChannelFuture connect, BackendView target) {
        if (!frontend.eventLoop().inEventLoop()) {
            try {
                frontend.eventLoop().execute(() -> finishInitialConnection(connect, target));
            } catch (RejectedExecutionException shutdown) {
                connect.channel().close();
            }
            return;
        }
        if (closed.get() || disconnecting || backend != connect.channel()) {
            connect.channel().close();
            return;
        }
        if (!connect.isSuccess()) {
            LOGGER.debug("Initial backend connection failed for player {} to {}", view.username(), target.address(),
                    connect.cause());
            backend = null;
            connect.channel().close();
            connectNextInitialBackend();
            return;
        }
        BackendView current = owner.catalog().find(target.handle().id()).orElse(null);
        if (current == null || !current.handle().equals(target.handle())
                || !current.address().equals(target.address())) {
            backend = null;
            connect.channel().close();
            connectNextInitialBackend();
            return;
        }
        if (initialRoutingDeadlineNanos - System.nanoTime() <= 0) {
            disconnectLogin("Initial server routing timed out.");
            return;
        }
        selected = current;
        backendConnected = true;
        initialCandidates = List.of();
        resetLoginDeadline(owner.loginStageTimeout());
        backend.pipeline().addLast("initial-session", Session.this);
        backend.config().setAutoRead(true);
        LOGGER.debug("Initial backend TCP connection established for player {} to {}",
                view.username(), selected.address());
        ByteBuf handshakeBody = null;
        ByteBuf loginBody;
        try {
            handshakeBody = encodeBackendHandshake(
                    selected.handle().id().value(), selected.owner().instanceGeneration());
            loginBody = new LoginStart(view.username()).encode(frontend.alloc(), ProtocolProfile.minecraft1710());
        } catch (RuntimeException failure) {
            ReferenceCountUtil.release(handshakeBody);
            disconnectLogin("Could not prepare backend login.");
            return;
        }
        backend.write(handshakeBody);
        backend.writeAndFlush(loginBody).addListener(write -> {
            if (write.isSuccess()) {
                LOGGER.debug("Initial backend login frames flushed for player {} to {}",
                        view.username(), selected.address());
            } else {
                LOGGER.debug("Initial backend login write failed for player {} to {}",
                        view.username(), selected.address(), write.cause());
                disconnectLogin("Could not start login on the selected server.");
            }
        });
    }

    private void disconnectLogin(String reason) { disconnectLogin(Component.text(reason)); }

    private void disconnectLogin(Component message) {
        String reason;
        try { reason = MinecraftText.encodeReason(message); }
        catch (RuntimeException invalid) {
            LOGGER.warn("Invalid plugin login rejection message", invalid);
            reason = MinecraftText.encodeReason(Component.text("Connection rejected."));
        }
        if (frontend.eventLoop().inEventLoop()) beginDisconnect(reason);
        else requestDisconnect(reason);
    }

    private void resetLoginDeadline(Duration timeout) {
        if (initialLoginDeadline != null) initialLoginDeadline.cancel(false);
        initialLoginDeadline = frontend.eventLoop().schedule(() -> {
                    LOGGER.debug("Login phase deadline expired for player {} with backend {}; disconnect started={}",
                            view == null ? "<unknown>" : view.username(),
                            selected == null ? "<none>" : selected.handle().id().value(), loginDisconnectStarted);
                    if (loginStart != null && !loginDisconnectStarted && !published) {
                        disconnectLogin("Login timed out.");
                    } else {
                        closePair();
                    }
                },
                timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void receiveBackend(ByteBuf packet) {
        if (loginDisconnectStarted) return;
        ByteBuf input = packet.duplicate();
        int id = ProtocolVarInt.read(input);
        if (id == 1) { // Encryption Request means backend is not in offline mode.
            LOGGER.debug("Backend requested encryption for player {}", view.username());
            closePair();
            return;
        }
        if (id == 0) { // Login Disconnect.
            loginDisconnectStarted = true;
            disconnecting = true;
            if (tabCompletion != null) tabCompletion.close();
            disconnectResult = new CompletableFuture<>();
            frontend.config().setAutoRead(false);
            resetLoginDeadline(LOGIN_DISCONNECT_DRAIN_TIMEOUT);
            frontend.writeAndFlush(packet.copy()).addListener(ignored -> closePair());
            return;
        }
        if (id != 2 || published) {
            LOGGER.debug("Unexpected backend login packet {} for player {}", id, view.username());
            closePair();
            return;
        }
        MinecraftLoginSuccess success = MinecraftLoginSuccess.decode(packet);
        if (!success.username().equals(view.username())) {
            LOGGER.debug("Backend login name mismatch for player {}: {}", view.username(), success.username());
            closePair();
            return;
        }
        UUID expected = owner.onlineMode() ? verifiedProfile.uuid()
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + success.username()).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(success.playerId())) {
            LOGGER.debug("Backend login UUID mismatch for player {}", view.username());
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
        owner.serverConnected(view, Optional.empty());
        if (closed.get() || disconnecting) return;
        relayStarting = true;
        frontend.config().setAutoRead(false);
        if (frontend.pipeline().get("login-wait-guard") != null) {
            frontend.pipeline().remove("login-wait-guard");
        }
        backend.config().setAutoRead(false);
        // Outbound order is the protocol boundary: anything submitted after Login Success
        // must use PLAY packet identifiers, even while its write promise is still pending.
        frontendPlayPhase = true;
        frontend.writeAndFlush(packet.copy()).addListener(write -> {
            if (!write.isSuccess()) { closePair(); return; }
            frontend.eventLoop().execute(this::startRelay);
        });
    }

    private void allowFrontendPlayFrames() {
        SessionChannels.frameDecoder(frontend.pipeline(), "client frame decoder missing after login success")
                .allowPlayFrames();
    }

    private void startRelay() {
        Channel target = backend;
        if (closed.get() || disconnecting) return;
        if (target == null || !frontend.isActive() || !target.isActive()) { closePair(); return; }
        try {
            CompletableFuture<Void> buffered = flushTransitionFrames();
            buffered.whenComplete((ignored, bufferFailure) -> {
                if (disconnecting) return;
                if (bufferFailure != null) { closePair(); return; }
                attachRawRelay();
            });
        } catch (RuntimeException failure) {
            closePair();
        }
    }

    private void attachRawRelay() {
        Channel target = backend;
        if (closed.get() || disconnecting) return;
        if (target == null || !frontend.isActive() || !target.isActive()) { closePair(); return; }
        try {
            var observation = playObservation;
            frontend.pipeline().addLast("keep-alive-bridge", new KeepAliveBridge(keepAlives, true, this::closePair));
            if (owner.commandDispatcher() != null) {
                frontend.pipeline().addLast("player-commands", new PlayerCommandInterceptor(
                        this::dispatchPlayerCommand, this::closePair));
            }
            target.pipeline().addLast("keep-alive-bridge", new KeepAliveBridge(keepAlives, false, this::closePair));
            installTabCompletion(target);
            var link = RawRelay.attach(frontend, target,
                    bytes -> observation.observeFrame(false, bytes),
                    bytes -> observation.observeFrame(true, bytes));
            observation.ready().whenComplete((ignored, failure) -> link.stopObserving());
            link.ready().whenComplete((ignored, failure) -> {
                if (disconnecting) return;
                if (failure != null) { closePair(); return; }
                CompletableFuture<Void> frontRemoved = removeHandshakeCodecs(frontend);
                CompletableFuture<Void> backRemoved = removeHandshakeCodecs(target);
                CompletableFuture.allOf(frontRemoved, backRemoved).whenComplete((removed, removeFailure) -> {
                    if (disconnecting) return;
                    if (removeFailure != null) closePair();
                    else {
                        relay = link;
                        relayStarting = false;
                        link.start();
                        tryStartPendingTransfer();
                    }
                });
            });
        } catch (RuntimeException failure) {
            closePair();
        }
    }

    private void installTabCompletion(Channel target) {
        if (!owner.hasCommandCompletion()) return;
        if (tabCompletion != null) tabCompletion.close();
        if (frontend.pipeline().get("tab-completion") != null) frontend.pipeline().remove("tab-completion");
        tabCompletion = new TabCompletionBridge(frontend, prefix -> owner.commandNames(view, prefix),
                text -> owner.completeCommand(view, text), this::closePair,
                suggestion -> owner.commandVisible(view, suggestion));
        frontend.pipeline().addLast("tab-completion", tabCompletion.frontendHandler());
        target.pipeline().addLast("tab-completion", tabCompletion.backendHandler());
    }

    private boolean dispatchPlayerCommand(String message) {
        if (closed.get() || disconnecting || !published) return false;
        return owner.commandDispatcher().dispatch(view, message, this::sendCommandReply, this::admitPlayerCommand);
    }

    private boolean admitPlayerCommand() {
        CommandRateLimiter.Decision decision = commandLimiter.admit(System.nanoTime());
        if (decision == CommandRateLimiter.Decision.DENY_WITH_NOTICE) {
            sendCommandReply(Component.text("Too many proxy commands. Please slow down."));
        }
        return decision == CommandRateLimiter.Decision.ADMIT;
    }

    CompletionStage<MessageResult> sendMessage(String message) {
        validateMessage(message);
        return sendMessage(Component.text(message));
    }

    CompletionStage<MessageResult> sendMessage(Component message) {
        return sendEncodedMessage(MinecraftText.encode(message));
    }

    CompletionStage<MessageResult> sendEncodedMessage(String message) {
        CompletableFuture<MessageResult> result = new CompletableFuture<>();
        if (closed.get() || disconnecting) {
            result.complete(MessageResult.NOT_CONNECTED);
            return result;
        }
        while (true) {
            int pending = pendingMessages.get();
            if (pending >= MAX_PENDING_MESSAGES) {
                result.complete(MessageResult.BACKPRESSURED);
                return result;
            }
            if (pendingMessages.compareAndSet(pending, pending + 1)) break;
        }
        Runnable send = () -> {
            if (closed.get() || !frontend.isActive()) {
                finishMessage();
                result.complete(MessageResult.NOT_CONNECTED);
                return;
            }
            if (disconnecting) {
                finishMessage();
                result.complete(MessageResult.NOT_CONNECTED);
                return;
            }
            if (!published || !frontendPlayPhase || relayStarting || relay == null
                    || transfer != null || pendingTransfer != null || playObservation == null
                    || !playObservation.ready().isDone() || playObservation.ready().isCompletedExceptionally()) {
                finishMessage();
                result.complete(MessageResult.NOT_READY);
                return;
            }
            if (!frontend.isWritable()) {
                finishMessage();
                result.complete(MessageResult.BACKPRESSURED);
                return;
            }
            try {
                ByteBuf frame = Minecraft1710PlayPackets.chatReplyEncoded(frontend.alloc(), message);
                frontend.writeAndFlush(frame).addListener(write -> {
                    pendingMessages.decrementAndGet();
                    if (write.isSuccess()) result.complete(MessageResult.SENT);
                    else {
                        result.completeExceptionally(write.cause());
                        closePair();
                    }
                    if (disconnecting) writeDisconnectReasonIfDrained();
                });
            } catch (RuntimeException failure) {
                pendingMessages.decrementAndGet();
                result.completeExceptionally(failure);
                if (disconnecting) writeDisconnectReasonIfDrained();
            }
        };
        if (frontend.eventLoop().inEventLoop()) send.run();
        else {
            try { frontend.eventLoop().execute(send); }
            catch (RejectedExecutionException shutdown) {
                pendingMessages.decrementAndGet();
                result.complete(MessageResult.NOT_CONNECTED);
            }
        }
        return result;
    }

    static void validateMessage(String message) {
        if (message == null || message.codePointCount(0, message.length()) > 1024) {
            throw new IllegalArgumentException("message must contain at most 1024 Unicode code points");
        }
    }

    private void finishMessage() {
        pendingMessages.decrementAndGet();
        if (disconnecting) writeDisconnectReasonIfDrained();
    }

    private void sendCommandReply(Component message) {
        sendMessage(message);
    }

    CompletionStage<DisconnectResult> disconnect(String reason) {
        validateDisconnectReason(reason);
        return requestDisconnect(MinecraftText.encodeReason(Component.text(reason)));
    }

    CompletionStage<DisconnectResult> disconnectEncoded(String reason) {
        return requestDisconnect(reason);
    }

    static void validateDisconnectReason(String reason) {
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 1024) {
            throw new IllegalArgumentException("disconnect reason must contain 1 to 1024 Unicode code points");
        }
    }

    private CompletionStage<DisconnectResult> requestDisconnect(String reason) {
        CompletableFuture<DisconnectResult> requested = new CompletableFuture<>();
        Runnable command = () -> {
            if (closed.get()) {
                requested.complete(DisconnectResult.NOT_CONNECTED);
                return;
            }
            beginDisconnect(reason);
            // Listener shutdown can set closed from another thread between the check above
            // and beginDisconnect. That path never creates a draining-disconnect future.
            if (disconnectResult == null) requested.complete(DisconnectResult.NOT_CONNECTED);
            else disconnectResult.whenComplete((value, failure) -> completeFrom(requested, value, failure));
        };
        if (frontend.eventLoop().inEventLoop()) command.run();
        else {
            try { frontend.eventLoop().execute(command); }
            catch (RejectedExecutionException shutdown) { requested.complete(DisconnectResult.NOT_CONNECTED); }
        }
        return requested;
    }

    private void beginDisconnect(String reason) {
        if (closed.get() || disconnecting) return;
        disconnecting = true;
        if (tabCompletion != null) tabCompletion.close();
        loginDisconnectStarted = true;
        pendingDisconnectReason = reason;
        disconnectResult = new CompletableFuture<>();
        frontend.config().setAutoRead(false);
        if (initialLoginDeadline != null) initialLoginDeadline.cancel(false);
        if (initialPlayDeadline != null) initialPlayDeadline.cancel(false);
        try {
            disconnectDeadline = frontend.eventLoop().schedule(this::closePair,
                    LOGIN_DISCONNECT_DRAIN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            RawRelay.Link activeRelay = relay;
            if (activeRelay == null) {
                disconnectRelayDrained = true;
                writeDisconnectReasonIfDrained();
            }
            else activeRelay.pause().whenComplete((ignored, failure) -> {
                try {
                    frontend.eventLoop().execute(() -> {
                        if (!disconnecting || closed.get()) return;
                        if (failure != null) closePair();
                        else {
                            disconnectRelayDrained = true;
                            writeDisconnectReasonIfDrained();
                        }
                    });
                } catch (RejectedExecutionException shutdown) {
                    closePair();
                }
            });
        } catch (RuntimeException shutdown) {
            closePair();
        }
    }

    private void writeDisconnectReasonIfDrained() {
        if (!disconnecting || !disconnectRelayDrained || disconnectPacketStarted || closed.get()
                || pendingMessages.get() != 0) return;
        disconnectPacketStarted = true;
        if (!frontend.isActive()) {
            closePair();
            return;
        }
        try {
            ByteBuf packet;
            if (frontendPlayPhase) {
                ByteBuf payload = Minecraft1710PlayPackets.disconnectEncoded(frontend.alloc(), pendingDisconnectReason);
                try {
                    packet = frontend.pipeline().get("minecraft-frame-encoder") == null
                            ? Minecraft1710PlayPackets.frame(frontend.alloc(), payload) : payload.copy();
                } finally {
                    payload.release();
                }
            } else {
                packet = MinecraftLoginDisconnect.encodeJson(frontend.alloc(), pendingDisconnectReason);
            }
            frontend.writeAndFlush(packet).addListener(ignored -> closePair());
        } catch (RuntimeException failure) {
            closePair();
        }
    }

    private static <T> void completeFrom(CompletableFuture<T> target, T value, Throwable failure) {
        if (failure == null) target.complete(value);
        else target.completeExceptionally(failure);
    }

    boolean matchesIdentity(PlayerIdentity requested) {
        PlayerView current = view;
        return !closed.get() && current != null && current.identity().equals(requested);
    }

    private void bufferTransitionFrame(boolean fromFrontend, ByteBuf packet) {
        int bytes = packet.readableBytes();
        if (transitionBuffer.size() >= MAX_TRANSITION_BUFFER_FRAMES
                || transitionBufferBytes + bytes > MAX_TRANSITION_BUFFER_BYTES) {
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
            ByteBuf payload;
            try {
                payload = mapTransitionFrame(pending, target);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
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

    /**
     * Takes ownership of a buffered frame and returns the buffer to send, or null when the frame is
     * swallowed. The frame is released on every failure, so callers never touch it after an exception.
     */
    private ByteBuf mapTransitionFrame(PendingFrame pending, Channel target) {
        ByteBuf payload = pending.payload;
        try {
            if (playObservation != null) playObservation.observePacket(!pending.fromFrontend, payload);
            if (target == null || !target.isActive()) throw new IllegalStateException("transition peer closed");
            ByteBuf mapped = keepAlives.body(target.alloc(), payload, pending.fromFrontend);
            if (mapped != payload) payload.release();
            return mapped;
        } catch (RuntimeException failure) {
            payload.release();
            throw failure;
        }
    }

    private CompletableFuture<Void> removeHandshakeCodecs(Channel channel) {
        return removeHandshakeCodecs(channel, () -> { });
    }

    private CompletableFuture<Void> removeHandshakeCodecs(Channel channel, Runnable beforeRemoval) {
        CompletableFuture<Void> removed = new CompletableFuture<>();
        channel.eventLoop().execute(() -> {
            try {
                beforeRemoval.run();
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get("initial-session") != null) pipeline.remove("initial-session");
                if (pipeline.get("transfer-candidate") != null) pipeline.remove("transfer-candidate");
                if (pipeline.get("minecraft-frame-encoder") != null) pipeline.remove("minecraft-frame-encoder");
                if (pipeline.get("session-lifecycle") == null) pipeline.addLast("session-lifecycle", new ChannelInboundHandlerAdapter() {
                    @Override public void channelInactive(ChannelHandlerContext ctx) {
                        if (ctx.channel() == frontend || (ctx.channel() == backend
                                && (relay == null || !relay.hasPendingClientboundWrites()))) closePair();
                        ctx.fireChannelInactive();
                    }

                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        if (ctx.channel() == backend && relay != null && relay.hasPendingClientboundWrites()) {
                            ctx.close();
                        } else if (ctx.channel() == frontend || ctx.channel() == backend) {
                            closePair();
                        }
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
        Runnable command = () -> {
            try {
                beginTransfer(backendName, result);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
                closePair();
            }
        };
        if (frontend.eventLoop().inEventLoop()) command.run();
        else {
            try {
                frontend.eventLoop().execute(command);
            } catch (RejectedExecutionException shutdown) {
                result.complete(TransferResult.of(TransferStatus.PLAYER_NOT_CONNECTED));
            }
        }
        return result;
    }

    private void beginTransfer(String backendName, CompletableFuture<TransferResult> result) {
        if (closed.get() || disconnecting || !published) {
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
        if (target == null || !SessionChannels.isTcpAddress(target.address())) {
            result.complete(TransferResult.of(TransferStatus.SERVER_UNAVAILABLE));
            return;
        }
        TransferAttempt attempt = new TransferAttempt(target, result, relay);
        attempt.preparation = owner.selectTransferPreparation();
        if (attempt.preparation != null) {
            Duration totalBudget = owner.eventTimeout().multipliedBy(2).plusSeconds(20)
                    .plus(owner.transferCutoverTimeout()).plus(FORGE_TRANSFER_HANDSHAKE_TIMEOUT);
            attempt.context = new TransferContext(UUID.randomUUID(), identity, serverView(selected), serverView(target),
                    selected.handle().generation(), target.handle().generation(),
                    selected.owner().instanceGeneration(), target.owner().instanceGeneration(),
                    Instant.now().plus(totalBudget));
            attempt.totalDeadline = frontend.eventLoop().schedule(() -> {
                if (currentAttempt(attempt)) failTransfer(attempt, "coordinated transfer timed out");
            }, totalBudget.toNanos(), TimeUnit.NANOSECONDS);
        }
        transfer = attempt;
        LOGGER.debug("Starting replacement backend connection for player {} to {}",
                view.username(), target.address());
        attempt.candidate = new TransferCandidate(identity.playerId(), view.username(), new TransferCandidate.Listener() {
            @Override public void ready(TransferCandidate candidate) { candidateReady(attempt); }
            @Override public void failed(TransferCandidate candidate, String reason) { failTransfer(attempt, reason); }
        }, attempt.preparation != null);
        try {
            Bootstrap bootstrap = SessionChannels.backendBootstrap(frontend, owner.transport(),
                    owner.backendResolver(), 5000, true,
                    pipeline -> pipeline.addLast("transfer-candidate", attempt.candidate));
            ChannelFuture connect = bootstrap.connect(SessionChannels.socketAddress(attempt.target.address()));
            attempt.channel = connect.channel();
            connect.addListener(future -> {
                if (attempt.finished || closed.get() || disconnecting) { connect.channel().close(); return; }
                if (!future.isSuccess()) {
                    LOGGER.debug("Replacement backend connection failed for player {} to {}",
                            view.username(), attempt.target.address(), future.cause());
                    failTransfer(attempt, "could not connect to replacement backend");
                    return;
                }
                LOGGER.debug("Replacement backend TCP connection established for player {} to {}",
                        view.username(), attempt.target.address());
                if (attempt.preparation == null) writeBackendLogin(connect.channel());
                else prepareTransfer(attempt);
            });
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not start replacement backend connection");
        }
    }

    private static ServerView serverView(BackendView backend) {
        return new ServerView(backend.handle().id().value(), backend.address(), backend.tags(), backend.metadata());
    }

    private boolean currentAttempt(TransferAttempt attempt) {
        return transfer == attempt && !attempt.finished && !closed.get() && !disconnecting;
    }

    private boolean targetStillCurrent(TransferAttempt attempt) {
        BackendView current = owner.catalog().find(attempt.target.handle().id()).orElse(null);
        return current != null && current.handle().equals(attempt.target.handle())
                && current.owner().equals(attempt.target.owner()) && current.address().equals(attempt.target.address())
                && attempt.channel != null && attempt.channel.isActive();
    }

    private void prepareTransfer(TransferAttempt attempt) {
        try {
            CompletionStage<PreparedTransfer> preparation = attempt.preparation.prepare(
                    new TransferPreparingEvent(attempt.context));
            attempt.coordination = preparation.toCompletableFuture();
            preparation.whenComplete((prepared, failure) -> frontend.eventLoop().execute(() -> {
                if (!currentAttempt(attempt)) return;
                attempt.coordination = null;
                if (failure != null || prepared == null) {
                    LOGGER.debug("Transfer preparation failed for {}", view.username(), failure);
                    failTransfer(attempt, "transfer preparation failed");
                } else if (!targetStillCurrent(attempt)) {
                    failTransfer(attempt, "replacement backend registration changed");
                } else if (prepared.requiresSourceRelease()) {
                    releaseTransferSource(attempt, prepared);
                } else {
                    writeBackendLogin(attempt.channel);
                }
            }));
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not prepare backend transfer");
        }
    }

    private void releaseTransferSource(TransferAttempt attempt, PreparedTransfer prepared) {
        Channel source = backend;
        pauseSource(attempt, source, "could not stop source gameplay for transfer",
                "could not drain source relay", () -> {
            if (!currentAttempt(attempt)) {
                if (!closed.get() && !disconnecting) resumeAfterFailedTransfer(attempt);
                return;
            }
            if (clientHasPartialFrame()) {
                failTransfer(attempt, "client packet was incomplete at the transfer boundary");
                return;
            }
            if (!targetStillCurrent(attempt)) {
                failTransfer(attempt, "replacement backend became unavailable");
                return;
            }
            attempt.oldRelay.detach().whenComplete((removed, detachFailure) -> frontend.eventLoop().execute(() -> {
                if (detachFailure != null) {
                    boolean attached = frontend.pipeline().get("raw-relay") != null
                            && source.pipeline().get("raw-relay") != null;
                    failTransfer(attempt, "could not detach source relay");
                    if (!attached) closePair();
                    return;
                }
                attempt.detached = true;
                if (!currentAttempt(attempt) || !targetStillCurrent(attempt)) {
                    failTransfer(attempt, "replacement backend closed during source release");
                    closePair();
                    return;
                }
                // Disarm exact old-channel EOF before closing it; no destination exists yet.
                attempt.sourceReleased = true;
                attempt.clientBuffer.discardFrames();
                attempt.oldBackendBuffer.discardFrames();
                backend = null;
                relay = null;
                view = new PlayerView(identity, view.username(), Optional.empty());
                owner.playerContextChanged(view);
                if (source.pipeline().get("transfer-frame-handler") != null) {
                    source.pipeline().remove("transfer-frame-handler");
                }
                source.close().addListener(closedSource -> {
                    if (!currentAttempt(attempt)) return;
                    if (!closedSource.isSuccess()) {
                        failTransfer(attempt, "could not close source connection");
                        return;
                    }
                    confirmSourceReleased(attempt, prepared);
                });
            }));
        });
    }

    private void confirmSourceReleased(TransferAttempt attempt, PreparedTransfer prepared) {
        try {
            CompletionStage<Void> confirmation = prepared.sourceClosed();
            attempt.coordination = confirmation.toCompletableFuture();
            confirmation.whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
                if (!currentAttempt(attempt)) return;
                attempt.coordination = null;
                if (failure != null) {
                    LOGGER.debug("Source release confirmation failed for {}", view.username(), failure);
                    failTransfer(attempt, "source release confirmation failed");
                } else if (!targetStillCurrent(attempt)) {
                    failTransfer(attempt, "replacement backend registration changed");
                } else writeBackendLogin(attempt.channel);
            }));
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not confirm source release");
        }
    }

    private void tryStartPendingTransfer() {
        PendingTransfer waiting = pendingTransfer;
        if (disconnecting || waiting == null || relay == null || !playObservation.ready().isDone()) return;
        pendingTransfer = null;
        waiting.deadline.cancel(false);
        beginTransfer(waiting.backendName, waiting.result);
    }

    private void writeBackendLogin(Channel channel) {
        transfer.candidate.loginStarted();
        ByteBuf handshakeBody = encodeBackendHandshake(
                transfer.target.handle().id().value(), transfer.target.owner().instanceGeneration());
        ByteBuf loginBody = new LoginStart(view.username()).encode(frontend.alloc(), ProtocolProfile.minecraft1710());
        channel.write(handshakeBody);
        channel.writeAndFlush(loginBody).addListener(write -> {
            if (write.isSuccess()) {
                LOGGER.debug("Replacement backend login frames flushed for player {} to {}",
                        view.username(), channel.remoteAddress());
            } else {
                LOGGER.debug("Replacement backend login write failed for player {}",
                        view.username(), write.cause());
            }
            if (!write.isSuccess() && transfer != null && transfer.channel == channel) {
                failTransfer(transfer, "could not write replacement backend login");
            }
        });
    }

    private ByteBuf encodeForwardedHandshake(String backendName, long backendEpoch) {
        byte[] secret = owner.sessionBindingSecret(backendName);
        try {
            return encodeForwardedHandshake(backendName, backendEpoch, verifiedProfile, secret);
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    private ByteBuf encodeBackendHandshake(String backendName, long backendEpoch) {
        if (owner.onlineMode()) return encodeForwardedHandshake(backendName, backendEpoch);
        byte[] secret = owner.sessionBindingSecret(backendName);
        if (secret == null) return handshake.encode(frontend.alloc(), ProtocolProfile.minecraft1710());
        try {
            // OFFLINE identity is derived by this proxy and is not a Mojang-authenticated profile.
            // Only forward it when this backend has explicitly configured session binding.
            VerifiedProfile offlineProfile = new VerifiedProfile(identity.playerId(), view.username(), List.of());
            return encodeForwardedHandshake(backendName, backendEpoch, offlineProfile, secret);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private ByteBuf encodeForwardedHandshake(String backendName, long backendEpoch,
                                             VerifiedProfile profile, byte[] secret) {
        String proof = null;
        if (secret != null && backendEpoch > 0) {
            long expiresAt = Math.addExact(System.currentTimeMillis(), ForwardedSessionProof.MAX_LIFETIME_MILLIS);
            proof = ForwardedSessionProof.create(owner.proxyEpoch(), identity.playerId(), identity.connectionId(),
                    backendName, backendEpoch, UUID.randomUUID(), expiresAt, secret);
        }
        return BungeeLegacyForwarding.encode(frontend.alloc(), handshake,
                (InetSocketAddress) frontend.remoteAddress(), profile, proof);
    }

    private void candidateReady(TransferAttempt attempt) {
        if (transfer != attempt || attempt.finished || closed.get() || disconnecting) return;
        if (attempt.preparation != null && !targetStillCurrent(attempt)) {
            failTransfer(attempt, "replacement backend registration changed during login");
            return;
        }
        attempt.cutoverDeadline = frontend.eventLoop().schedule(() -> {
            if (transfer != attempt || attempt.result.isDone()) return;
            attempt.result.complete(TransferResult.failed("replacement backend cutover timed out"));
            closePair();
        }, owner.transferCutoverTimeout().toNanos(), TimeUnit.NANOSECONDS);
        if (attempt.sourceReleased) {
            if (clientHasPartialFrame()) {
                failTransfer(attempt, "client packet was incomplete at replacement cutover");
                return;
            }
            activateCandidate(attempt);
            return;
        }
        pauseSource(attempt, backend, "could not buffer frames for transfer",
                "old backend stopped during transfer", () -> {
            if (attempt.finished || closed.get() || disconnecting) {
                if (disconnecting) return;
                resumeAfterFailedTransfer(attempt);
                return;
            }
            if (clientHasPartialFrame()) {
                failTransfer(attempt, "client packet was incomplete at the transfer boundary");
                return;
            }
            if (!attempt.channel.isActive()) {
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
                        if (closed.get() || disconnecting || !attempt.channel.isActive()) {
                            if (disconnecting) return;
                            failTransfer(attempt, "replacement backend closed during transfer");
                            closePair();
                            return;
                        }
                        attempt.detached = true;
                        activateCandidate(attempt);
                    }));
        });
    }

    /**
     * Buffers both old-link directions and pauses the old relay. {@code onPaused} runs on the session
     * event loop after accepted writes drain; buffer or drain failures fail the attempt and close.
     */
    private void pauseSource(TransferAttempt attempt, Channel source, String bufferFailure, String drainFailure,
                             Runnable onPaused) {
        try {
            attempt.clientBuffer = installTransferBuffer(frontend, "transfer-client-buffer");
            attempt.oldBackendBuffer = installTransferBuffer(source, "transfer-old-backend-buffer");
        } catch (RuntimeException failure) {
            failTransfer(attempt, bufferFailure);
            closePair();
            return;
        }
        attempt.pauseInProgress = true;
        CompletionStage<Void> pause = attempt.oldRelay.pause();
        frontend.eventLoop().execute(attempt.clientBuffer::readUntilRemoved);
        source.eventLoop().execute(attempt.oldBackendBuffer::readUntilRemoved);
        pause.whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
            attempt.pauseInProgress = false;
            if (failure != null) {
                failTransfer(attempt, drainFailure);
                closePair();
                return;
            }
            attempt.paused = true;
            onPaused.run();
        }));
    }

    private TransferFrameBuffer installTransferBuffer(Channel channel, String name) {
        var buffer = new TransferFrameBuffer(this::closePair);
        channel.pipeline().addAfter(SessionChannels.frameDecoderName(channel.pipeline()), name, buffer);
        return buffer;
    }

    private boolean clientHasPartialFrame() {
        return SessionChannels.frameDecoder(frontend.pipeline(), "client frame decoder missing during transfer")
                .hasPartialFrame();
    }

    private void activateCandidate(TransferAttempt attempt) {
        // Keep READY ownership until removal. A decoder can emit more target frames after
        // JoinGame in the same read batch; marking HANDED_OFF early would silently drop them.
        removeHandshakeCodecs(attempt.channel, attempt.candidate::handOff).whenComplete((ignored, failure) ->
                frontend.eventLoop().execute(() -> {
                    if (failure != null || closed.get() || disconnecting || !attempt.channel.isActive()) {
                        if (disconnecting) return;
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
            installTabCompletion(attempt.channel);
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
            if (failure != null || closed.get() || disconnecting
                    || (attempt.preparation != null && !targetStillCurrent(attempt))) {
                if (disconnecting) return;
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
                if (!write.isSuccess() || closed.get() || disconnecting || !attempt.channel.isActive()
                        || (attempt.preparation != null && !targetStillCurrent(attempt))) {
                    if (disconnecting) return;
                    failTransfer(attempt, "could not send replacement world transition");
                    closePair();
                    return;
                }
                Channel oldBackend = backend;
                Optional<String> previousServer = attempt.sourceReleased
                        ? Optional.of(attempt.context.source().name()) : view.currentServer();
                PlayObservation oldObservation = playObservation;
                TransferFrameHandler.State oldFrameState = frameState;
                backend = attempt.channel;
                selected = attempt.target;
                playObservation = nextObservation;
                relay = next;
                frameState = attempt.frameState;
                view = new PlayerView(identity, view.username(), selected.handle().id().value());
                owner.serverConnected(view, previousServer);
                if (oldBackend != null) oldBackend.close();
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
                        if (transfer != attempt || attempt.finished || closed.get() || disconnecting) return;
                        failTransfer(attempt, "replacement Forge handshake timed out");
                        closePair();
                    }, FORGE_TRANSFER_HANDSHAKE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
                    CompletableFuture.allOf(nextObservation.ready(), attempt.frameState.worldReady())
                            .whenComplete((negotiated, negotiationFailure) -> frontend.eventLoop().execute(() -> {
                                if (transfer != attempt || attempt.finished || closed.get() || disconnecting) return;
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
        if (transfer != attempt || attempt.finished || closed.get() || disconnecting) return;
        transfer = null;
        attempt.finished = true;
        attempt.cutoverDeadline.cancel(false);
        cancelCoordination(attempt);
        attempt.result.complete(TransferResult.of(TransferStatus.NETWORK_READY));
    }

    private void installTransferFrameHandlers(TransferFrameHandler.State state, Channel target) {
        ChannelPipeline clientPipeline = frontend.pipeline();
        if (clientPipeline.get("transfer-frame-handler") != null) clientPipeline.remove("transfer-frame-handler");
        if (SessionChannels.frameDecoderName(clientPipeline) == null) {
            throw new IllegalStateException("client frame decoder missing during transfer");
        }
        clientPipeline.addLast("transfer-frame-handler", new TransferFrameHandler(state, false));
        target.pipeline().addLast("transfer-frame-handler", new TransferFrameHandler(state, true));
    }

    private ByteBuf transferOpening(TransferAttempt attempt) {
        ByteBuf output = frontend.alloc().buffer();
        List<ByteBuf> queued = attempt.candidate.takeQueuedPackets();
        try {
            boolean nextForge = attempt.candidate.observation().forgeSeen();
            // FML's reset also restores the client's frozen registry. A Forge -> vanilla
            // switch needs it even though the replacement server has no ServerHello.
            // After that reset the client remains in HELLO until a later Forge switch.
            if (!clientFmlAwaitingServerHello && (playObservation.forgeSeen() || nextForge)) {
                ByteBuf reset = Minecraft1710PlayPackets.forgeReset(frontend.alloc());
                try { output.writeBytes(reset); } finally { reset.release(); }
                clientFmlAwaitingServerHello = true;
            }
            if (nextForge) clientFmlAwaitingServerHello = false;
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
        LOGGER.debug("Replacement backend failed for player {} to {}: {}; channel registered={}, active={}",
                view.username(), attempt.target.address(), reason,
                attempt.channel != null && attempt.channel.isRegistered(),
                attempt.channel != null && attempt.channel.isActive());
        attempt.finished = true;
        attempt.failureReason = reason;
        cancelCoordination(attempt);
        if (attempt.channel != null) attempt.channel.close();
        if (attempt.candidate != null) attempt.candidate.close();
        if (attempt.frameState != null) attempt.frameState.close();
        if (disconnecting) return;
        if (!attempt.pauseInProgress) resumeAfterFailedTransfer(attempt);
    }

    private void resumeAfterFailedTransfer(TransferAttempt attempt) {
        if (attempt.paused && !attempt.detached && !closed.get() && !disconnecting) {
            attempt.oldRelay.resume().whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
                if (failure != null) closePair();
                completeFailedTransfer(attempt);
            }));
        } else {
            completeFailedTransfer(attempt);
        }
    }

    private void completeFailedTransfer(TransferAttempt attempt) {
        if (!attempt.detached && !closed.get() && !disconnecting) {
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
        if (attempt.detached) closePair();
    }

    private static void cancelCoordination(TransferAttempt attempt) {
        if (attempt.totalDeadline != null) attempt.totalDeadline.cancel(false);
        CompletableFuture<?> work = attempt.coordination;
        attempt.coordination = null;
        // Cancellation may run arbitrary CompletionStage continuations, never on session I/O.
        if (work != null) Thread.startVirtualThread(() -> work.cancel(false));
    }

    void closePair() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = () -> {
            if (tabCompletion != null) tabCompletion.close();
            if (initialLoginDeadline != null) initialLoginDeadline.cancel(false);
            if (initialPlayDeadline != null) initialPlayDeadline.cancel(false);
            if (disconnectDeadline != null) disconnectDeadline.cancel(false);
            if (verification != null) verification.cancel(true);
            if (admissionRequest != null) {
                admissionRequest.cancel(false);
                admissionRequest = null;
            }
            if (placementRequest != null) {
                placementRequest.cancel(false);
                placementRequest = null;
            }
            frontend.close();
            Channel upstream = backend;
            if (upstream != null) upstream.close();
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
                cancelCoordination(activeTransfer);
                if (activeTransfer.cutoverDeadline != null) activeTransfer.cutoverDeadline.cancel(false);
                if (activeTransfer.channel != null) activeTransfer.channel.close();
                if (activeTransfer.candidate != null) activeTransfer.candidate.close();
                if (activeTransfer.frameState != null) activeTransfer.frameState.close();
                activeTransfer.result.complete(TransferResult.failed("player session closed during transfer"));
            }
            if (identityClaimed) {
                owner.releasePermissions(identity);
                owner.releaseIdentity(identity.playerId(), this);
            }
            if (published) {
                owner.sessionUnpublished();
                owner.playerDisconnected(view);
            }
            owner.allSessions().remove(this);
            owner.sessionClosed();
            PendingFrame pending;
            while ((pending = transitionBuffer.pollFirst()) != null) pending.payload.release();
            transitionBufferBytes = 0;
            if (disconnectResult != null) disconnectResult.complete(DisconnectResult.DISCONNECTED);
        };
        if (frontend.eventLoop().inEventLoop()) cleanup.run();
        else {
            try {
                frontend.eventLoop().execute(cleanup);
            } catch (RejectedExecutionException shutdown) {
                // Once the loop has stopped, no session task can race this cleanup.
                // If shutdown is still in progress, wait for its last task to finish.
                if (frontend.eventLoop().isTerminated()) cleanup.run();
                else frontend.eventLoop().terminationFuture().addListener(ignored -> cleanup.run());
            }
        }
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        if (ctx.channel() != frontend && ctx.channel() != backend) return;
        if (!published && loginStart != null) {
            LOGGER.debug("{} channel closed during initial login for player {} with backend {}",
                    ctx.channel() == frontend ? "Client" : "Backend", loginStart.username(),
                    selected == null ? "<none>" : selected.handle().id().value());
        }
        if (ctx.channel() == backend && backendConnected && !published && loginStart != null
                && !loginDisconnectStarted) {
            disconnectLogin("Selected server closed during login.");
            return;
        }
        if (ctx.channel() != backend || !loginDisconnectStarted) closePair();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ctx.channel() != frontend && ctx.channel() != backend) {
            ctx.close();
            return;
        }
        LOGGER.debug("{} channel exception for player {}", ctx.channel() == frontend ? "Client" : "Backend",
                view == null ? "<unknown>" : view.username(), cause);
        if (ctx.channel() == backend && loginDisconnectStarted) ctx.close();
        else closePair();
    }

    Optional<PlayerView> onlineView() {
        if (!published || closed.get()) return Optional.empty();
        return Optional.of(view);
    }

    private record PendingFrame(boolean fromFrontend, ByteBuf payload) { }
}
