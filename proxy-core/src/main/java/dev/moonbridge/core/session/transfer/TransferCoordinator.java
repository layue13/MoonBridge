package dev.moonbridge.core.session.transfer;

import dev.moonbridge.api.ServerView;
import dev.moonbridge.api.TransferResult;
import dev.moonbridge.api.TransferStatus;
import dev.moonbridge.api.event.TransferContext;
import dev.moonbridge.api.event.TransferPreparingEvent;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.event.PreparedTransfer;
import dev.moonbridge.core.protocol.ByteBufs;
import dev.moonbridge.core.protocol.LoginStart;
import dev.moonbridge.core.protocol.ProtocolProfile;
import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.relay.RawRelay;
import dev.moonbridge.core.session.channel.SessionChannels;
import dev.moonbridge.core.session.play.KeepAliveBridge;
import dev.moonbridge.core.session.play.PlayObservation;
import dev.moonbridge.core.session.play.TransitionFrames;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Moves a connected player to another backend while the old link keeps serving: dials and logs into the
 * candidate, buffers both directions, swaps relays and rewrites the player's entity ids. Everything runs on
 * the player's frontend event loop; the session is reached only through {@link TransferHost}.
 */
public final class TransferCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransferCoordinator.class);
    private static final Duration FORGE_TRANSFER_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    private final TransferHost host;
    private final Channel frontend;
    private PendingTransfer pendingTransfer;
    private TransferAttempt transfer;
    private TransferFrameHandler.State frameState;
    private Integer clientEntityId;
    private boolean clientFmlAwaitingServerHello;

    public TransferCoordinator(TransferHost host) {
        this.host = host;
        this.frontend = host.frontend();
    }

    /** True while a transfer is queued or running; other writers to the client must wait. */
    public boolean busy() { return transfer != null || pendingTransfer != null; }

    public CompletionStage<TransferResult> transferTo(String backendName) {
        CompletableFuture<TransferResult> result = new CompletableFuture<>();
        Runnable command = () -> {
            try {
                beginTransfer(backendName, result);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
                host.closeSession();
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
        if (host.closed() || host.disconnecting() || !host.published()) {
            result.complete(TransferResult.of(TransferStatus.PLAYER_NOT_CONNECTED));
            return;
        }
        if (transfer != null || pendingTransfer != null) {
            result.complete(TransferResult.failed("a backend transfer is already in progress"));
            return;
        }
        if (host.selected().handle().id().value().equals(backendName)) {
            BackendView current = host.catalog().find(host.selected().handle().id()).orElse(null);
            if (current != null && current.address().equals(host.selected().address())) {
                result.complete(TransferResult.of(TransferStatus.NETWORK_READY));
                return;
            }
        }
        if (host.observation().ready().isCompletedExceptionally()) {
            result.complete(TransferResult.failed("backend login or Forge negotiation failed"));
            return;
        }
        if (host.relay() == null || !host.observation().ready().isDone()) {
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
            target = host.catalog().find(new BackendId(backendName)).orElse(null);
        } catch (IllegalArgumentException invalidName) {
            target = null;
        }
        if (target == null || !SessionChannels.isTcpAddress(target.address())) {
            result.complete(TransferResult.of(TransferStatus.SERVER_UNAVAILABLE));
            return;
        }
        TransferAttempt attempt = new TransferAttempt(target, result, host.relay());
        attempt.preparation = host.selectPreparation();
        if (attempt.preparation != null) {
            Duration totalBudget = host.eventTimeout().multipliedBy(2).plusSeconds(20)
                    .plus(host.cutoverTimeout()).plus(FORGE_TRANSFER_HANDSHAKE_TIMEOUT);
            attempt.context = new TransferContext(UUID.randomUUID(), host.identity(), serverView(host.selected()), serverView(target),
                    host.selected().handle().generation(), target.handle().generation(),
                    host.selected().owner().instanceGeneration(), target.owner().instanceGeneration(),
                    Instant.now().plus(totalBudget));
            attempt.totalDeadline = frontend.eventLoop().schedule(() -> {
                if (currentAttempt(attempt)) failTransfer(attempt, "coordinated transfer timed out");
            }, totalBudget.toNanos(), TimeUnit.NANOSECONDS);
        }
        transfer = attempt;
        LOGGER.debug("Starting replacement backend connection for player {} to {}",
                host.view().username(), target.address());
        attempt.candidate = new TransferCandidate(host.identity().playerId(), host.view().username(), new TransferCandidate.Listener() {
            @Override public void ready(TransferCandidate candidate) { candidateReady(attempt); }
            @Override public void failed(TransferCandidate candidate, String reason) { failTransfer(attempt, reason); }
        }, attempt.preparation != null);
        try {
            Bootstrap bootstrap = SessionChannels.backendBootstrap(frontend, host.transport(),
                    host.resolver(), 5000, true,
                    pipeline -> pipeline.addLast("transfer-candidate", attempt.candidate));
            ChannelFuture connect = bootstrap.connect(SessionChannels.socketAddress(attempt.target.address()));
            attempt.channel = connect.channel();
            connect.addListener(future -> {
                if (attempt.finished || host.closed() || host.disconnecting()) { connect.channel().close(); return; }
                if (!future.isSuccess()) {
                    LOGGER.debug("Replacement backend connection failed for player {} to {}",
                            host.view().username(), attempt.target.address(), future.cause());
                    failTransfer(attempt, "could not connect to replacement backend");
                    return;
                }
                LOGGER.debug("Replacement backend TCP connection established for player {} to {}",
                        host.view().username(), attempt.target.address());
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
        return transfer == attempt && !attempt.finished && !host.closed() && !host.disconnecting();
    }

    private boolean targetStillCurrent(TransferAttempt attempt) {
        BackendView current = host.catalog().find(attempt.target.handle().id()).orElse(null);
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
                    LOGGER.debug("Transfer preparation failed for {}", host.view().username(), failure);
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
        Channel source = host.backend();
        pauseSource(attempt, source, "could not stop source gameplay for transfer",
                "could not drain source relay", () -> {
            if (!currentAttempt(attempt)) {
                if (!host.closed() && !host.disconnecting()) resumeAfterFailedTransfer(attempt);
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
                    if (!attached) host.closeSession();
                    return;
                }
                attempt.detached = true;
                if (!currentAttempt(attempt) || !targetStillCurrent(attempt)) {
                    failTransfer(attempt, "replacement backend closed during source release");
                    host.closeSession();
                    return;
                }
                // Disarm exact old-channel EOF before closing it; no destination exists yet.
                attempt.sourceReleased = true;
                attempt.clientBuffer.discardFrames();
                attempt.oldBackendBuffer.discardFrames();
                host.sourceReleased();
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
                    LOGGER.debug("Source release confirmation failed for {}", host.view().username(), failure);
                    failTransfer(attempt, "source release confirmation failed");
                } else if (!targetStillCurrent(attempt)) {
                    failTransfer(attempt, "replacement backend registration changed");
                } else writeBackendLogin(attempt.channel);
            }));
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not confirm source release");
        }
    }

    /** Starts the queued transfer once the relay is attached and the PLAY/Forge handshake has finished. */
    public void startPending() {
        PendingTransfer waiting = pendingTransfer;
        if (host.disconnecting() || waiting == null || host.relay() == null || !host.observation().ready().isDone()) return;
        pendingTransfer = null;
        waiting.deadline.cancel(false);
        beginTransfer(waiting.backendName, waiting.result);
    }

    private void writeBackendLogin(Channel channel) {
        transfer.candidate.loginStarted();
        ByteBuf handshakeBody = host.backendHandshake(
                transfer.target.handle().id().value(), transfer.target.owner().instanceGeneration());
        ByteBuf loginBody = new LoginStart(host.view().username()).encode(frontend.alloc(), ProtocolProfile.minecraft1710());
        channel.write(handshakeBody);
        channel.writeAndFlush(loginBody).addListener(write -> {
            if (write.isSuccess()) {
                LOGGER.debug("Replacement backend login frames flushed for player {} to {}",
                        host.view().username(), channel.remoteAddress());
            } else {
                LOGGER.debug("Replacement backend login write failed for player {}",
                        host.view().username(), write.cause());
            }
            if (!write.isSuccess() && transfer != null && transfer.channel == channel) {
                failTransfer(transfer, "could not write replacement backend login");
            }
        });
    }

    private void candidateReady(TransferAttempt attempt) {
        if (transfer != attempt || attempt.finished || host.closed() || host.disconnecting()) return;
        if (attempt.preparation != null && !targetStillCurrent(attempt)) {
            failTransfer(attempt, "replacement backend registration changed during login");
            return;
        }
        attempt.cutoverDeadline = frontend.eventLoop().schedule(() -> {
            if (transfer != attempt || attempt.result.isDone()) return;
            attempt.result.complete(TransferResult.failed("replacement backend cutover timed out"));
            host.closeSession();
        }, host.cutoverTimeout().toNanos(), TimeUnit.NANOSECONDS);
        if (attempt.sourceReleased) {
            if (clientHasPartialFrame()) {
                failTransfer(attempt, "client packet was incomplete at replacement cutover");
                return;
            }
            activateCandidate(attempt);
            return;
        }
        pauseSource(attempt, host.backend(), "could not buffer frames for transfer",
                "old backend stopped during transfer", () -> {
            if (attempt.finished || host.closed() || host.disconnecting()) {
                if (host.disconnecting()) return;
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
                                    && host.backend().pipeline().get("raw-relay") != null;
                            failTransfer(attempt, "could not detach old backend relay");
                            if (!oldRelayStillAttached) host.closeSession();
                            return;
                        }
                        if (host.closed() || host.disconnecting() || !attempt.channel.isActive()) {
                            if (host.disconnecting()) return;
                            failTransfer(attempt, "replacement backend closed during transfer");
                            host.closeSession();
                            return;
                        }
                        attempt.detached = true;
                        activateCandidate(attempt);
                    }));
        });
    }

    /**
     * Buffers both old-link directions and pauses the old host.relay(). {@code onPaused} runs on the session
     * event loop after accepted writes drain; buffer or drain failures fail the attempt and close.
     */
    private void pauseSource(TransferAttempt attempt, Channel source, String bufferFailure, String drainFailure,
                             Runnable onPaused) {
        try {
            attempt.clientBuffer = installTransferBuffer(frontend, "transfer-client-buffer");
            attempt.oldBackendBuffer = installTransferBuffer(source, "transfer-old-backend-buffer");
        } catch (RuntimeException failure) {
            failTransfer(attempt, bufferFailure);
            host.closeSession();
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
                host.closeSession();
                return;
            }
            attempt.paused = true;
            onPaused.run();
        }));
    }

    private TransferFrameBuffer installTransferBuffer(Channel channel, String name) {
        var buffer = new TransferFrameBuffer(host::closeSession);
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
        host.swapHandshakeCodecs(attempt.channel, attempt.candidate::handOff).whenComplete((ignored, failure) ->
                frontend.eventLoop().execute(() -> {
                    if (failure != null || host.closed() || host.disconnecting() || !attempt.channel.isActive()) {
                        if (host.disconnecting()) return;
                        failTransfer(attempt, "replacement backend pipeline failed");
                        host.closeSession();
                        return;
                    }
                    installCandidateRelay(attempt);
                }));
    }

    private void installCandidateRelay(TransferAttempt attempt) {
        PlayObservation nextObservation = attempt.candidate.observation();
        RawRelay.Link next;
        try {
            if (clientEntityId == null) clientEntityId = host.observation().entityId().orElseThrow();
            attempt.frameState = new TransferFrameHandler.State(frontend, attempt.channel, nextObservation,
                    clientEntityId, attempt.candidate.joinGame(), host::closeSession);
            attempt.channel.pipeline().addLast("keep-alive-bridge",
                    new KeepAliveBridge(host.keepAlives(), false, host::closeSession));
            installTransferFrameHandlers(attempt.frameState, attempt.channel);
            host.installTabCompletion(attempt.channel);
            next = RawRelay.attach(frontend, attempt.channel,
                    bytes -> nextObservation.observeFrame(false, bytes),
                    bytes -> nextObservation.observeFrame(true, bytes));
            RawRelay.Link observedLink = next;
            nextObservation.ready().whenComplete((ignored, failure) -> observedLink.stopObserving());
        } catch (RuntimeException failure) {
            failTransfer(attempt, "could not attach replacement relay");
            host.closeSession();
            return;
        }
        next.ready().whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
            if (failure != null || host.closed() || host.disconnecting()
                    || (attempt.preparation != null && !targetStillCurrent(attempt))) {
                if (host.disconnecting()) return;
                failTransfer(attempt, "replacement relay was not ready");
                host.closeSession();
                return;
            }
            ByteBuf opening;
            try {
                host.keepAlives().switchBackend();
                opening = transferOpening(attempt);
            } catch (RuntimeException malformed) {
                failTransfer(attempt, "could not prepare replacement world transition");
                host.closeSession();
                return;
            }
            frontend.writeAndFlush(opening).addListener(write -> frontend.eventLoop().execute(() -> {
                if (!write.isSuccess() || host.closed() || host.disconnecting() || !attempt.channel.isActive()
                        || (attempt.preparation != null && !targetStillCurrent(attempt))) {
                    if (host.disconnecting()) return;
                    failTransfer(attempt, "could not send replacement world transition");
                    host.closeSession();
                    return;
                }
                Channel oldBackend = host.backend();
                Optional<String> previousServer = attempt.sourceReleased
                        ? Optional.of(attempt.context.source().name()) : host.view().currentServer();
                PlayObservation oldObservation = host.observation();
                TransferFrameHandler.State oldFrameState = frameState;
                frameState = attempt.frameState;
                host.cutover(attempt.channel, attempt.target, nextObservation, next, previousServer);
                if (oldBackend != null) oldBackend.close();
                oldObservation.close();
                if (oldFrameState != null) oldFrameState.close();
                try {
                    attempt.clientBuffer.drainAndRemove();
                } catch (RuntimeException replayFailure) {
                    host.closeSession();
                    return;
                }
                if (host.closed()) return;
                next.start();
                if (host.closed()) return;
                if (nextObservation.forgeSeen()) {
                    attempt.cutoverDeadline.cancel(false);
                    attempt.cutoverDeadline = frontend.eventLoop().schedule(() -> {
                        if (transfer != attempt || attempt.finished || host.closed() || host.disconnecting()) return;
                        failTransfer(attempt, "replacement Forge handshake timed out");
                        host.closeSession();
                    }, FORGE_TRANSFER_HANDSHAKE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
                    CompletableFuture.allOf(nextObservation.ready(), attempt.frameState.worldReady())
                            .whenComplete((negotiated, negotiationFailure) -> frontend.eventLoop().execute(() -> {
                                if (transfer != attempt || attempt.finished || host.closed() || host.disconnecting()) return;
                                if (negotiationFailure != null || !attempt.channel.isActive()) {
                                    failTransfer(attempt, "replacement Forge handshake failed");
                                    host.closeSession();
                                } else finishTransfer(attempt);
                            }));
                } else finishTransfer(attempt);
            }));
        }));
    }

    private void finishTransfer(TransferAttempt attempt) {
        if (transfer != attempt || attempt.finished || host.closed() || host.disconnecting()) return;
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
        List<ByteBuf> queued = attempt.candidate.takeQueuedPackets();
        try {
            return ByteBufs.fill(frontend.alloc().buffer(), output -> {
                boolean nextForge = attempt.candidate.observation().forgeSeen();
                // FML's reset also restores the client's frozen registry. A Forge -> vanilla
                // switch needs it even though the replacement server has no ServerHello.
                // After that reset the client remains in HELLO until a later Forge switch.
                if (!clientFmlAwaitingServerHello && (host.observation().forgeSeen() || nextForge)) {
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
                    ByteBuf mapped = host.keepAlives().body(frontend.alloc(), packet, false);
                    if (mapped == null) continue;
                    try {
                        TransitionFrames.appendClientbound(output, frontend.alloc(), mapped,
                                attempt.candidate.joinGame() == null ? null : attempt.candidate.joinGame().entityId(),
                                clientEntityId);
                    } finally {
                        if (mapped != packet) mapped.release();
                    }
                }
            });
        } finally {
            queued.forEach(ByteBuf::release);
        }
    }

    private void failTransfer(TransferAttempt attempt, String reason) {
        if (attempt.finished) return;
        LOGGER.debug("Replacement backend failed for player {} to {}: {}; channel registered={}, active={}",
                host.view().username(), attempt.target.address(), reason,
                attempt.channel != null && attempt.channel.isRegistered(),
                attempt.channel != null && attempt.channel.isActive());
        attempt.finished = true;
        attempt.failureReason = reason;
        cancelCoordination(attempt);
        if (attempt.channel != null) attempt.channel.close();
        if (attempt.candidate != null) attempt.candidate.close();
        if (attempt.frameState != null) attempt.frameState.close();
        if (host.disconnecting()) return;
        if (!attempt.pauseInProgress) resumeAfterFailedTransfer(attempt);
    }

    private void resumeAfterFailedTransfer(TransferAttempt attempt) {
        if (attempt.paused && !attempt.detached && !host.closed() && !host.disconnecting()) {
            attempt.oldRelay.resume().whenComplete((ignored, failure) -> frontend.eventLoop().execute(() -> {
                if (failure != null) host.closeSession();
                completeFailedTransfer(attempt);
            }));
        } else {
            completeFailedTransfer(attempt);
        }
    }

    private void completeFailedTransfer(TransferAttempt attempt) {
        if (!attempt.detached && !host.closed() && !host.disconnecting()) {
            try {
                if (attempt.oldBackendBuffer != null) attempt.oldBackendBuffer.drainAndRemove();
                if (attempt.clientBuffer != null) attempt.clientBuffer.drainAndRemove();
            } catch (RuntimeException failure) {
                host.closeSession();
            }
        }
        if (attempt.cutoverDeadline != null) attempt.cutoverDeadline.cancel(false);
        if (transfer == attempt) transfer = null;
        attempt.result.complete(TransferResult.failed(attempt.failureReason));
        if (attempt.detached) host.closeSession();
    }

    private static void cancelCoordination(TransferAttempt attempt) {
        if (attempt.totalDeadline != null) attempt.totalDeadline.cancel(false);
        CompletableFuture<?> work = attempt.coordination;
        attempt.coordination = null;
        // Cancellation may run arbitrary CompletionStage continuations, never on session I/O.
        if (work != null) Thread.startVirtualThread(() -> work.cancel(false));
    }
    /** Session teardown: fails any queued or running transfer and releases the frame state. */
    public void close() {
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
    }
}
