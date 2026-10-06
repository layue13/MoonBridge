package dev.moonbridge.core.session;

import dev.moonbridge.core.session.channel.SessionChannels;
import dev.moonbridge.core.session.play.CommandRateLimiter;
import dev.moonbridge.core.session.play.KeepAliveBridge;
import dev.moonbridge.core.session.play.PlayObservation;
import dev.moonbridge.core.session.play.PlayerCommandInterceptor;
import dev.moonbridge.core.session.play.TabCompletionBridge;
import dev.moonbridge.core.session.play.TransitionBuffer;
import dev.moonbridge.core.session.play.TransitionFrames;
import dev.moonbridge.core.session.transfer.TransferCoordinator;
import dev.moonbridge.core.session.transfer.TransferHost;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.core.event.TransferPreparation;
import dev.moonbridge.api.event.PlayerAdmissionEvent;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.protocol.LoginStart;
import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.MinecraftLoginSuccess;
import dev.moonbridge.core.protocol.MinecraftLoginDisconnect;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import dev.moonbridge.core.relay.RawRelay;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.event.TransferPreparation;
import dev.moonbridge.core.net.NetworkTransport;
import io.netty.resolver.AddressResolverGroup;
import java.util.function.BiConsumer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Objects;
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
    private static final Duration LOGIN_DISCONNECT_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private final ProxySessionListener owner;
    private final Channel frontend;
    private volatile Channel backend;
    private boolean backendConnected;
    private PlayerIdentity identity;
    private boolean identityClaimed;
    private volatile PlayerView view;
    private BackendView selected;
    private CompletableFuture<Optional<PlacementDecision>> placementRequest;
    private CompletableFuture<AccessDecision> admissionRequest;
    private boolean loginDisconnectStarted;
    private volatile boolean published;
    private boolean relayStarting;
    private final TransitionBuffer transitionBuffer =
            new TransitionBuffer(MAX_TRANSITION_BUFFER_FRAMES, MAX_TRANSITION_BUFFER_BYTES);
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
    private final FrontendLogin login;
    private final InitialRouter router;
    private final TransferCoordinator transfers;

    Session(ProxySessionListener owner, Channel frontend) {
        this.owner = owner;
        this.frontend = frontend;
        this.login = new FrontendLogin(owner, frontend, this::closePair, () -> closed.get() || disconnecting,
                this::beginLogin);
        this.router = new InitialRouter(owner, frontend, () -> closed.get() || disconnecting,
                new InitialRouter.Listener() {
                    @Override public void connected(Channel channel, BackendView target) {
                        initialBackendConnected(channel, target);
                    }
                    @Override public void reject(Component reason) { disconnectLogin(reason); }
                    @Override public void close() { closePair(); }
                });
        this.transfers = new TransferCoordinator(new Host());
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
            if (ctx.channel() == frontend) login.receive(body);
            else receiveBackend(body);
        } catch (RuntimeException failure) {
            LOGGER.debug("Closing session after {} frame handling failed",
                    ctx.channel() == frontend ? "client" : "backend", failure);
            closePair();
        } finally {
            packet.release();
        }
    }

    private void beginLogin(UUID uuid, String username) {
        identity = new PlayerIdentity(uuid, owner.allocateConnectionId());
        if (!owner.claimIdentity(uuid, this)) {
            closePair();
            return;
        }
        identityClaimed = true;
        view = new PlayerView(identity, username, Optional.empty());
        login.guardPendingLogin();
        resetLoginDeadline(owner.eventTimeout().plusSeconds(1));
        CompletionStage<Void> prepared;
        try {
            prepared = owner.preparePermissions(view);
        } catch (RuntimeException failure) {
            LOGGER.debug("Could not prepare permissions for {}", view.username(), failure);
            disconnectLogin("Could not load permissions. Please try again.");
            return;
        }
        resumeOnLoop(prepared, (ignored, failure) -> {
            if (closed.get() || disconnecting || loginDisconnectStarted) return;
            if (failure != null) {
                LOGGER.debug("Permission preparation failed for {}", view.username(), failure);
                disconnectLogin("Could not load permissions. Please try again.");
            } else {
                beginPlayerAdmission();
            }
        });
    }

    private void beginPlayerAdmission() {
        if (!owner.hasSubscribers(PlayerAdmissionEvent.class)) {
            beginPlacement();
            return;
        }
        resetLoginDeadline(owner.eventTimeout().plusSeconds(1));
        CompletableFuture<AccessDecision> request;
        try {
            request = Objects.requireNonNull(owner.dispatchEvent(
                    new PlayerAdmissionEvent(view, (InetSocketAddress) frontend.remoteAddress(),
                            login.verifiedProfile() != null)),
                    "player admission stage").toCompletableFuture();
        } catch (RuntimeException failure) {
            LOGGER.debug("Could not start player admission for {}", view.username(), failure);
            disconnectLogin("Could not check login access. Please try again.");
            return;
        }
        admissionRequest = request;
        resumeOnLoop(request, (decision, failure) -> {
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
    }

    private void beginPlacement() {
        router.startClock();
        resetLoginDeadline(owner.placementTimeout());
        CompletableFuture<Optional<PlacementDecision>> request = owner.placement().apply(view).toCompletableFuture();
        placementRequest = request;
        resumeOnLoop(request, (decision, failure) -> {
            if (placementRequest == request) placementRequest = null;
            if (closed.get() || disconnecting) return;
            if (failure != null || decision == null) {
                disconnectLogin("Could not select a server. Please try again.");
                return;
            }
            router.route(view.username(), decision);
        });
    }

    /** Re-enters the frontend event loop from whichever thread completed {@code stage}. */
    private <T> void resumeOnLoop(CompletionStage<T> stage, BiConsumer<T, Throwable> handler) {
        stage.whenComplete((value, failure) -> {
            try {
                frontend.eventLoop().execute(() -> handler.accept(value, failure));
            } catch (RejectedExecutionException shutdown) {
                closePair();
            }
        });
    }

    private void initialBackendConnected(Channel channel, BackendView target) {
        backend = channel;
        selected = target;
        backendConnected = true;
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
                    if (login.loginStart() != null && !loginDisconnectStarted && !published) {
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
        UUID expected = owner.onlineMode() ? login.verifiedProfile().uuid()
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
                if (failure == null) transfers.startPending();
            });
        });
        view = new PlayerView(identity, view.username(), selected.handle().id().value());
        published = true;
        owner.sessionPublished();
        owner.serverConnected(view, Optional.empty());
        if (closed.get() || disconnecting) return;
        relayStarting = true;
        frontend.config().setAutoRead(false);
        login.releaseGuard();
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
                        transfers.startPending();
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
                    || transfers.busy() || playObservation == null
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
        if (!transitionBuffer.add(fromFrontend, packet)) closePair();
    }

    private CompletableFuture<Void> flushTransitionFrames() {
        if (transitionBuffer.isEmpty()) return CompletableFuture.completedFuture(null);
        ArrayList<CompletableFuture<Void>> writes = new ArrayList<>(transitionBuffer.size());
        TransitionBuffer.Frame pending;
        while ((pending = transitionBuffer.poll()) != null) {
            Channel target = pending.fromFrontend() ? backend : frontend;
            ByteBuf payload;
            try {
                payload = TransitionFrames.map(playObservation, keepAlives, pending.fromFrontend(),
                        pending.payload(), target);
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


    private ByteBuf encodeBackendHandshake(String backendName, long backendEpoch) {
        return BackendHandshakes.encode(owner, frontend, login.handshake(), identity, view.username(), login.verifiedProfile(),
                backendName, backendEpoch);
    }

    CompletionStage<TransferResult> transferTo(String backendName) { return transfers.transferTo(backendName); }

    void closePair() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable cleanup = () -> {
            if (tabCompletion != null) tabCompletion.close();
            if (initialLoginDeadline != null) initialLoginDeadline.cancel(false);
            if (initialPlayDeadline != null) initialPlayDeadline.cancel(false);
            if (disconnectDeadline != null) disconnectDeadline.cancel(false);
            login.cancel();
            router.cancel();
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
            transfers.close();
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
            transitionBuffer.release();
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
        if (!published && login.loginStart() != null) {
            LOGGER.debug("{} channel closed during initial login for player {} with backend {}",
                    ctx.channel() == frontend ? "Client" : "Backend", login.loginStart().username(),
                    selected == null ? "<none>" : selected.handle().id().value());
        }
        if (ctx.channel() == backend && backendConnected && !published && login.loginStart() != null
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

    /** Adapter exposing this session's connection state to its {@link TransferCoordinator}. */
    private final class Host implements TransferHost {
        @Override public Channel frontend() { return frontend; }
        @Override public Channel backend() { return backend; }
        @Override public RawRelay.Link relay() { return relay; }
        @Override public BackendView selected() { return selected; }
        @Override public PlayerView view() { return view; }
        @Override public PlayerIdentity identity() { return identity; }
        @Override public PlayObservation observation() { return playObservation; }
        @Override public KeepAliveBridge.State keepAlives() { return keepAlives; }
        @Override public boolean published() { return published; }
        @Override public boolean closed() { return closed.get(); }
        @Override public boolean disconnecting() { return disconnecting; }
        @Override public void closeSession() { closePair(); }
        @Override public BackendCatalog catalog() { return owner.catalog(); }
        @Override public TransferPreparation selectPreparation() { return owner.selectTransferPreparation(); }
        @Override public Duration eventTimeout() { return owner.eventTimeout(); }
        @Override public Duration cutoverTimeout() { return owner.transferCutoverTimeout(); }
        @Override public NetworkTransport transport() { return owner.transport(); }
        @Override public AddressResolverGroup<InetSocketAddress> resolver() { return owner.backendResolver(); }
        @Override public ByteBuf backendHandshake(String backendName, long backendEpoch) {
            return encodeBackendHandshake(backendName, backendEpoch);
        }
        @Override public void installTabCompletion(Channel target) { Session.this.installTabCompletion(target); }
        @Override public CompletableFuture<Void> swapHandshakeCodecs(Channel channel, Runnable beforeRemoval) {
            return removeHandshakeCodecs(channel, beforeRemoval);
        }

        @Override public void sourceReleased() {
            backend = null;
            relay = null;
            view = new PlayerView(identity, view.username(), Optional.empty());
            owner.playerContextChanged(view);
        }

        @Override public void cutover(Channel next, BackendView target, PlayObservation observation,
                                      RawRelay.Link link, Optional<String> previousServer) {
            backend = next;
            selected = target;
            playObservation = observation;
            relay = link;
            view = new PlayerView(identity, view.username(), selected.handle().id().value());
            owner.serverConnected(view, previousServer);
        }
    }
}
