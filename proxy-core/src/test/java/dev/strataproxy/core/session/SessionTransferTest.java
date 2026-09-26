package dev.strataproxy.core.session;

import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.TransferStatus;
import dev.strataproxy.api.event.Event;
import dev.strataproxy.api.event.PlayerDisconnectedEvent;
import dev.strataproxy.api.event.ServerConnectedEvent;
import dev.strataproxy.core.event.EventDispatcher;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendCatalog;
import dev.strataproxy.core.backend.BackendHandle;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.BackendView;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SessionTransferTest {
    private static final String USERNAME = "IslandPlayer";
    private static final UUID PLAYER_ID = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + USERNAME).getBytes(StandardCharsets.UTF_8));

    @Test
    void clientFrameWaitsForWorldTransitionDuringCutover() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newReceived = new CompletableFuture<byte[]>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    acceptLogin(new DataInputStream(socket.getInputStream()),
                            new DataOutputStream(socket.getOutputStream()), 0);
                    assertEquals(-1, socket.getInputStream().read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    sendJoinGame(output, 0, 200);
                    writeFrame(output, new byte[]{0x08});
                    newReceived.complete(readFrame(input));
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newReceived.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    var input = new DataInputStream(client.getInputStream());
                    sendLogin(new DataOutputStream(client.getOutputStream()));
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);

                    var session = listener.allSessions().iterator().next();
                    var frontendField = Session.class.getDeclaredField("frontend");
                    frontendField.setAccessible(true);
                    Channel frontend = (Channel) frontendField.get(session);
                    var openingHeld = new CompletableFuture<Void>();
                    var heldMessage = new AtomicReference<Object>();
                    var heldPromise = new AtomicReference<ChannelPromise>();
                    var heldContext = new AtomicReference<ChannelHandlerContext>();
                    frontend.eventLoop().submit(() -> frontend.pipeline().addFirst("hold-opening",
                            new ChannelDuplexHandler() {
                                @Override public void write(ChannelHandlerContext ctx, Object message,
                                                            ChannelPromise promise) {
                                    heldMessage.set(message);
                                    heldPromise.set(promise);
                                    heldContext.set(ctx);
                                    openingHeld.complete(null);
                                }

                                @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    ReferenceCountUtil.release(heldMessage.getAndSet(null));
                                    ChannelPromise promise = heldPromise.getAndSet(null);
                                    if (promise != null) promise.tryFailure(new IllegalStateException("frontend closed"));
                                    super.channelInactive(ctx);
                                }
                            })).get(5, TimeUnit.SECONDS);

                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    openingHeld.get(5, TimeUnit.SECONDS);
                    frontend.eventLoop().submit(() -> frontend.pipeline().fireChannelRead(
                            Unpooled.wrappedBuffer(new byte[]{2, 0x01, 0x55}))).get(5, TimeUnit.SECONDS);
                    assertThrows(java.util.concurrent.TimeoutException.class,
                            () -> newReceived.get(250, TimeUnit.MILLISECONDS));

                    frontend.eventLoop().submit(() -> {
                        Object message = heldMessage.getAndSet(null);
                        ChannelPromise promise = heldPromise.getAndSet(null);
                        frontend.pipeline().remove("hold-opening");
                        heldContext.get().writeAndFlush(message, promise);
                    }).get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.NETWORK_READY, transfer.get(5, TimeUnit.SECONDS).status());
                    assertEquals(7, packetId(readFrame(input)));
                    assertEquals(7, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    assertArrayEquals(new byte[]{0x01, 0x55}, newReceived.get(5, TimeUnit.SECONDS));
                    oldClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void incompleteClientFrameRollsBackAndReplaysBufferedFrames() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldReceived = new CompletableFuture<Void>();
            var newClosed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    acceptLogin(input, new DataOutputStream(socket.getOutputStream()), 0);
                    assertArrayEquals(new byte[]{0x01, 0x44}, readFrame(input));
                    assertArrayEquals(new byte[]{0x01, 0x55}, readFrame(input));
                    assertArrayEquals(new byte[]{0x01, 0x66}, readFrame(input));
                    oldReceived.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { oldReceived.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    sendJoinGame(output, 0, 200);
                    writeFrame(output, new byte[]{0x08});
                    assertEquals(-1, input.read());
                    newClosed.complete(null);
                } catch (Throwable failure) { newClosed.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    var input = new DataInputStream(client.getInputStream());
                    var output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);

                    var session = listener.allSessions().iterator().next();
                    var backendField = Session.class.getDeclaredField("backend");
                    backendField.setAccessible(true);
                    Channel oldChannel = (Channel) backendField.get(session);
                    var frontendField = Session.class.getDeclaredField("frontend");
                    frontendField.setAccessible(true);
                    Channel frontend = (Channel) frontendField.get(session);
                    var firstWriteHeld = new CompletableFuture<Void>();
                    var holdNext = new AtomicBoolean(true);
                    var heldMessage = new AtomicReference<Object>();
                    var heldPromise = new AtomicReference<ChannelPromise>();
                    var heldContext = new AtomicReference<ChannelHandlerContext>();
                    oldChannel.eventLoop().submit(() -> oldChannel.pipeline().addFirst("hold-one-old-write",
                            new ChannelDuplexHandler() {
                                @Override public void write(ChannelHandlerContext ctx, Object message,
                                                            ChannelPromise promise) {
                                    if (!holdNext.compareAndSet(true, false)) {
                                        ctx.write(message, promise);
                                        return;
                                    }
                                    heldMessage.set(message);
                                    heldPromise.set(promise);
                                    heldContext.set(ctx);
                                    firstWriteHeld.complete(null);
                                }

                                @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    ReferenceCountUtil.release(heldMessage.getAndSet(null));
                                    ChannelPromise promise = heldPromise.getAndSet(null);
                                    if (promise != null) promise.tryFailure(new IllegalStateException("old backend closed"));
                                    super.channelInactive(ctx);
                                }
                            })).get(5, TimeUnit.SECONDS);
                    writeFrame(output, new byte[]{0x01, 0x44});
                    firstWriteHeld.get(5, TimeUnit.SECONDS);

                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    boolean bufferInstalled = false;
                    for (int i = 0; i < 200 && !bufferInstalled; i++) {
                        bufferInstalled = frontend.eventLoop().submit(() ->
                                frontend.pipeline().get("transfer-client-buffer") != null)
                                .get(1, TimeUnit.SECONDS);
                        if (!bufferInstalled) Thread.sleep(5);
                    }
                    assertTrue(bufferInstalled, "candidate should reach the cutover buffer");
                    frontend.eventLoop().submit(() -> frontend.pipeline().fireChannelRead(
                            Unpooled.wrappedBuffer(new byte[]{2, 0x01, 0x55, 2, 0x01})))
                            .get(5, TimeUnit.SECONDS);
                    oldChannel.eventLoop().submit(() -> oldChannel.pipeline().fireChannelRead(
                            Unpooled.wrappedBuffer(new byte[]{2, 0x03, 0x77}))).get(5, TimeUnit.SECONDS);
                    oldChannel.eventLoop().submit(() -> {
                        Object message = heldMessage.getAndSet(null);
                        ChannelPromise promise = heldPromise.getAndSet(null);
                        heldContext.get().writeAndFlush(message, promise);
                    }).get(5, TimeUnit.SECONDS);

                    var result = transfer.get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.FAILED, result.status());
                    assertTrue(result.detail().orElse("").contains("incomplete"));
                    assertEquals("old", listener.find(player.identity()).orElseThrow()
                            .currentServer().orElseThrow());
                    assertArrayEquals(new byte[]{0x03, 0x77}, readFrame(input));
                    frontend.eventLoop().submit(() -> frontend.pipeline().fireChannelRead(
                            Unpooled.wrappedBuffer(new byte[]{0x66}))).get(5, TimeUnit.SECONDS);
                    oldReceived.get(5, TimeUnit.SECONDS);
                    newClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void oldBackendFrameDuringCutoverDoesNotCancelTransfer() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newReceived = new CompletableFuture<byte[]>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    acceptLogin(input, new DataOutputStream(socket.getOutputStream()), 0);
                    assertArrayEquals(new byte[]{0x01, 0x44}, readFrame(input));
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    sendJoinGame(output, 0, 200);
                    writeFrame(output, new byte[]{0x08});
                    newReceived.complete(readFrame(input));
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newReceived.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    var input = new DataInputStream(client.getInputStream());
                    var output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);

                    var session = listener.allSessions().iterator().next();
                    var backendField = Session.class.getDeclaredField("backend");
                    backendField.setAccessible(true);
                    Channel oldChannel = (Channel) backendField.get(session);
                    var frontendField = Session.class.getDeclaredField("frontend");
                    frontendField.setAccessible(true);
                    Channel frontend = (Channel) frontendField.get(session);
                    var firstWriteHeld = new CompletableFuture<Void>();
                    var holdNext = new AtomicBoolean(true);
                    var heldMessage = new AtomicReference<Object>();
                    var heldPromise = new AtomicReference<ChannelPromise>();
                    var heldContext = new AtomicReference<ChannelHandlerContext>();
                    oldChannel.eventLoop().submit(() -> oldChannel.pipeline().addFirst("hold-one-old-write",
                            new ChannelDuplexHandler() {
                                @Override public void write(ChannelHandlerContext ctx, Object message,
                                                            ChannelPromise promise) {
                                    if (!holdNext.compareAndSet(true, false)) {
                                        ctx.write(message, promise);
                                        return;
                                    }
                                    heldMessage.set(message);
                                    heldPromise.set(promise);
                                    heldContext.set(ctx);
                                    firstWriteHeld.complete(null);
                                }

                                @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    ReferenceCountUtil.release(heldMessage.getAndSet(null));
                                    ChannelPromise promise = heldPromise.getAndSet(null);
                                    if (promise != null) promise.tryFailure(new IllegalStateException("old backend closed"));
                                    super.channelInactive(ctx);
                                }
                            })).get(5, TimeUnit.SECONDS);
                    writeFrame(output, new byte[]{0x01, 0x44});
                    firstWriteHeld.get(5, TimeUnit.SECONDS);

                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    boolean bufferInstalled = false;
                    for (int i = 0; i < 200 && !bufferInstalled; i++) {
                        bufferInstalled = frontend.eventLoop().submit(() ->
                                frontend.pipeline().get("transfer-client-buffer") != null)
                                .get(1, TimeUnit.SECONDS);
                        if (!bufferInstalled) Thread.sleep(5);
                    }
                    assertTrue(bufferInstalled, "candidate should reach the cutover buffer");
                    oldChannel.eventLoop().submit(() -> oldChannel.pipeline().fireChannelRead(
                            Unpooled.wrappedBuffer(new byte[]{2, 0x03, 0x77}))).get(5, TimeUnit.SECONDS);
                    oldChannel.eventLoop().submit(() -> {
                        Object message = heldMessage.getAndSet(null);
                        ChannelPromise promise = heldPromise.getAndSet(null);
                        heldContext.get().writeAndFlush(message, promise);
                    }).get(5, TimeUnit.SECONDS);

                    assertEquals(TransferStatus.NETWORK_READY, transfer.get(5, TimeUnit.SECONDS).status());
                    assertEquals(7, packetId(readFrame(input)));
                    assertEquals(7, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    writeFrame(output, new byte[]{0x01, 0x55});
                    assertArrayEquals(new byte[]{0x01, 0x55}, newReceived.get(5, TimeUnit.SECONDS));
                    oldClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void cutoverTimesOutWhenOldBackendWriteNeverCompletes() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newClosed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    acceptLogin(new DataInputStream(socket.getInputStream()),
                            new DataOutputStream(socket.getOutputStream()), 0);
                    assertEquals(-1, socket.getInputStream().read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    acceptLogin(new DataInputStream(socket.getInputStream()),
                            new DataOutputStream(socket.getOutputStream()), 0, 200);
                    assertEquals(-1, socket.getInputStream().read());
                    newClosed.complete(null);
                } catch (Throwable failure) { newClosed.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(1));
            listener.setPlacement(ignored -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("old"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);

                    var session = listener.allSessions().iterator().next();
                    var backendField = Session.class.getDeclaredField("backend");
                    backendField.setAccessible(true);
                    Channel oldChannel = (Channel) backendField.get(session);
                    var blockedWrite = new CompletableFuture<Void>();
                    var heldMessage = new AtomicReference<Object>();
                    var heldPromise = new AtomicReference<ChannelPromise>();
                    oldChannel.eventLoop().submit(() -> oldChannel.pipeline().addFirst("hold-old-write",
                            new ChannelDuplexHandler() {
                                @Override public void write(ChannelHandlerContext ctx, Object message,
                                                            ChannelPromise promise) {
                                    heldMessage.set(message);
                                    heldPromise.set(promise);
                                    blockedWrite.complete(null);
                                }

                                @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    ReferenceCountUtil.release(heldMessage.getAndSet(null));
                                    ChannelPromise promise = heldPromise.getAndSet(null);
                                    if (promise != null) promise.tryFailure(new IllegalStateException("old backend closed"));
                                    super.channelInactive(ctx);
                                }
                            })).get(5, TimeUnit.SECONDS);
                    writeFrame(output, new byte[]{0x01, 0x55});
                    blockedWrite.get(5, TimeUnit.SECONDS);

                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.FAILED, transfer.status());
                    assertTrue(transfer.detail().orElse("").contains("cutover timed out"));
                    assertEquals(-1, input.read());
                    oldClosed.get(5, TimeUnit.SECONDS);
                    newClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void transfersVanillaPlayConnectionWithoutSecondLogin() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newRelayed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    if (input.read() != -1) throw new AssertionError("old backend received bytes after transfer");
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    sendJoinGame(output, 0, 200);
                    writeFrame(output, new byte[]{0x1C, 0, 0, 0, (byte) 200, 0x7F});
                    assertArrayEquals(new byte[]{0x01, 0x33}, readFrame(input));
                    writeFrame(output, new byte[]{0x08});
                    assertArrayEquals(new byte[]{0x0B, 0, 0, 0, (byte) 200, 1}, readFrame(input));
                    writeFrame(output, new byte[]{0x1A, 0, 0, 0, (byte) 200, 1});
                    writeFrame(output, new byte[]{0x03, 0x44});
                    newRelayed.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newRelayed.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            var notifications = new LinkedBlockingQueue<Event<?>>();
            listener.setEvents(new EventDispatcher() {
                @Override public boolean hasSubscribers(Class<?> type) {
                    return type == ServerConnectedEvent.class || type == PlayerDisconnectedEvent.class;
                }
                @Override public <R> CompletionStage<R> dispatch(Event<R> event) {
                    assertTrue(event instanceof ServerConnectedEvent || event instanceof PlayerDisconnectedEvent);
                    notifications.add(event);
                    return CompletableFuture.completedFuture(null);
                }
            }, Duration.ofSeconds(5));
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input))); // One login success for the whole client session.
                    assertEquals(1, packetId(readFrame(input))); // Initial Join Game.
                    assertEquals(8, packetId(readFrame(input))); // Initial Position and Look.
                    var player = awaitPlayer(listener);
                    assertEquals("old", player.currentServer().orElseThrow());
                    var entered = (ServerConnectedEvent) notifications.poll(5, TimeUnit.SECONDS);
                    assertEquals(Optional.empty(), entered.previousServer());
                    assertEquals(player, entered.player());
                    assertEquals(TransferStatus.SERVER_UNAVAILABLE,
                            listener.transfer(player.identity(), "missing").toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.NETWORK_READY, transfer.status(), transfer.detail().orElse(""));
                    assertEquals(7, packetId(readFrame(input))); // Dummy respawn for same dimension.
                    assertEquals(7, packetId(readFrame(input))); // Target dimension.
                    assertArrayEquals(new byte[]{0x1C, 0, 0, 0, 100, 0x7F}, readFrame(input));
                    writeFrame(output, new byte[]{0x01, 0x33});
                    assertEquals(8, packetId(readFrame(input))); // Target Position and Look, no second Login Success.
                    writeFrame(output, new byte[]{0x0B, 0, 0, 0, 100, 1});
                    assertArrayEquals(new byte[]{0x1A, 0, 0, 0, 100, 1}, readFrame(input));
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(input));
                    newRelayed.get(5, TimeUnit.SECONDS);
                    oldClosed.get(5, TimeUnit.SECONDS);
                    var current = listener.find(player.identity()).orElseThrow();
                    assertEquals("new", current.currentServer().orElseThrow());
                    assertEquals(current, listener.online().get(0));
                    var switched = (ServerConnectedEvent) notifications.poll(5, TimeUnit.SECONDS);
                    assertEquals(Optional.of("old"), switched.previousServer());
                    assertEquals(current, switched.player());
                    assertEquals(TransferStatus.NETWORK_READY,
                            listener.transfer(current.identity(), "new").toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
            var departed = (PlayerDisconnectedEvent) notifications.poll(5, TimeUnit.SECONDS);
            assertEquals(Optional.of("new"), departed.player().currentServer());
            listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(notifications.isEmpty(), "failed/no-op transfers and repeated close do not publish events");
        }
    }

    @Test
    void transferUsesCurrentAddressWhenDirectoryUpdatesBeforeLookup() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket staleTarget = server();
             ServerSocket movedTarget = server()) {
            movedTarget.setSoTimeout(5000);
            var oldClosed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    acceptLogin(input, new DataOutputStream(socket.getOutputStream()), 0);
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            BackendHandle targetHandle = register(catalog, "new", staleTarget);
            var moved = new BackendRegistration(targetHandle.id(), new BackendOwner("static", 0),
                    URI.create("tcp://127.0.0.1:" + movedTarget.getLocalPort()));
            var updated = new AtomicBoolean();
            BackendCatalog movingCatalog = new BackendCatalog() {
                @Override public BackendView register(BackendRegistration definition) {
                    return catalog.register(definition);
                }
                @Override public Optional<BackendView> update(BackendHandle handle, BackendRegistration definition) {
                    return catalog.update(handle, definition);
                }
                @Override public boolean remove(BackendHandle handle) { return catalog.remove(handle); }
                @Override public int removeOwner(BackendOwner value) { return catalog.removeOwner(value); }
                @Override public Optional<BackendView> find(BackendId id) {
                    if (id.equals(targetHandle.id()) && updated.compareAndSet(false, true)) {
                        catalog.update(targetHandle, moved).orElseThrow();
                    }
                    return catalog.find(id);
                }
                @Override public List<BackendView> snapshot() { return catalog.snapshot(); }
            };
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    movingCatalog);
            listener.setPlacement(ignored -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("old"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    var input = new DataInputStream(client.getInputStream());
                    sendLogin(new DataOutputStream(client.getOutputStream()));
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    try (Socket candidate = movedTarget.accept()) {
                        candidate.setSoTimeout(5000);
                        var candidateInput = new DataInputStream(candidate.getInputStream());
                        var candidateOutput = new DataOutputStream(candidate.getOutputStream());
                        acceptHandshakeAndLogin(candidateInput, candidateOutput);
                        sendLoginSuccess(candidateOutput);
                        sendJoinGame(candidateOutput, 0, 200);
                        writeFrame(candidateOutput, new byte[]{0x08});
                        assertEquals(TransferStatus.NETWORK_READY, transfer.get(5, TimeUnit.SECONDS).status());
                        assertEquals(7, packetId(readFrame(input)));
                        assertEquals(7, packetId(readFrame(input)));
                        assertEquals(8, packetId(readFrame(input)));
                        assertEquals(moved.address(), catalog.find(targetHandle.id()).orElseThrow().address());
                    }
                }
                oldClosed.get(5, TimeUnit.SECONDS);
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
            assertTrue(updated.get());
        }
    }

    @Test
    void sameNameWithNewAddressTransfersToCurrentRegistration() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newConnected = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0, 100);
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0, 200);
                    newConnected.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newConnected.completeExceptionally(failure); }
            });
            BackendHandle handle = register(catalog, "same", oldServer);
            var resolver = new DeferredBackendResolver("replacement.test");
            var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    catalog, null, Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(15), resolver);
            listener.setPlacement(ignored -> CompletableFuture.completedFuture(
                    Optional.of(PlacementDecision.select("same"))));
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    var input = new DataInputStream(client.getInputStream());
                    sendLogin(new DataOutputStream(client.getOutputStream()));
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);
                    var moved = new BackendRegistration(handle.id(), new BackendOwner("static", 0),
                            URI.create("tcp://replacement.test:" + newServer.getLocalPort()));
                    catalog.update(handle, moved).orElseThrow();

                    var transferFuture = listener.transfer(player.identity(), "same").toCompletableFuture();
                    var resolution = resolver.pending().get(5, TimeUnit.SECONDS);
                    resolution.executor().submit(() -> { }).get(1, TimeUnit.SECONDS);
                    assertTrue(!transferFuture.isDone(), "transfer waits for DNS without blocking the event loop");
                    resolution.release();
                    var transfer = transferFuture.get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.NETWORK_READY, transfer.status(), transfer.detail().orElse(""));
                    newConnected.get(5, TimeUnit.SECONDS);
                    assertEquals(7, packetId(readFrame(input)));
                    assertEquals(7, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    oldClosed.get(5, TimeUnit.SECONDS);
                    assertEquals("same", listener.find(player.identity()).orElseThrow()
                            .currentServer().orElseThrow());
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void staleKeepAliveReplyDoesNotReachTheReplacementBackend() throws Exception {
        int oldKeepAlive = 0x12345678;
        int newKeepAlive = 0x23456789;
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newReplied = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    writeFrame(output, keepAlive(oldKeepAlive));
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    sendJoinGame(output, 0, 200);
                    writeFrame(output, keepAlive(newKeepAlive));
                    writeFrame(output, new byte[]{0x08});
                    assertEquals(newKeepAlive, keepAliveId(readFrame(input)));
                    newReplied.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newReplied.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    int staleReply = keepAliveId(readFrame(input));
                    var player = awaitPlayer(listener);
                    assertEquals(TransferStatus.NETWORK_READY, listener.transfer(player.identity(), "new")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS).status());
                    assertEquals(7, packetId(readFrame(input)));
                    assertEquals(7, packetId(readFrame(input)));
                    int currentReply = keepAliveId(readFrame(input));
                    assertEquals(8, packetId(readFrame(input)));
                    assertTrue(staleReply != currentReply);
                    writeFrame(output, keepAlive(staleReply));
                    writeFrame(output, keepAlive(currentReply));
                    newReplied.get(5, TimeUnit.SECONDS);
                    oldClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void candidatePlayDisconnectBeforeCutoverKeepsOldBackendUsable() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldRelayed = new CompletableFuture<Void>();
            var newClosed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    assertArrayEquals(new byte[]{0x01, 0x55}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x66});
                    oldRelayed.complete(null);
                } catch (Throwable failure) { oldRelayed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    var batch = new ByteArrayOutputStream();
                    var packets = new DataOutputStream(batch);
                    sendJoinGame(packets, 0, 200);
                    writeFrame(packets, new byte[]{0x40, 0x00});
                    output.write(batch.toByteArray());
                    output.flush();
                    assertEquals(-1, input.read());
                    newClosed.complete(null);
                } catch (Throwable failure) { newClosed.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    readFrame(input); readFrame(input); readFrame(input);
                    var player = awaitPlayer(listener);
                    var result = listener.transfer(player.identity(), "new").toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.FAILED, result.status());
                    newClosed.get(5, TimeUnit.SECONDS);
                    assertEquals("old", listener.find(player.identity()).orElseThrow().currentServer().orElseThrow());
                    writeFrame(output, new byte[]{0x01, 0x55});
                    assertArrayEquals(new byte[]{0x03, 0x66}, readFrame(input));
                    oldRelayed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void failedCandidateLoginKeepsOldBackendUsable() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldRelayed = new CompletableFuture<Void>();
            var newClosed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    assertArrayEquals(new byte[]{0x01, 0x55}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x66});
                    oldRelayed.complete(null);
                } catch (Throwable failure) { oldRelayed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    readFrame(input);
                    readFrame(input);
                    newClosed.complete(null);
                } catch (Throwable failure) { newClosed.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    readFrame(input); readFrame(input); readFrame(input);
                    var player = awaitPlayer(listener);
                    var result = listener.transfer(player.identity(), "new").toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.FAILED, result.status());
                    newClosed.get(5, TimeUnit.SECONDS);
                    assertEquals("old", listener.find(player.identity()).orElseThrow().currentServer().orElseThrow());
                    writeFrame(output, new byte[]{0x01, 0x55});
                    assertArrayEquals(new byte[]{0x03, 0x66}, readFrame(input));
                    oldRelayed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void vanillaToForgeTransferRelaysTheFmlHandshakeInOrder() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newNegotiated = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    acceptLogin(input, new DataOutputStream(socket.getOutputStream()), 0);
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    writeFrame(output, serverForgeRegistration());
                    writeFrame(output, serverForgeHello(7));
                    assertArrayEquals(clientForgeRegistration(), readFrame(input));
                    assertArrayEquals(clientForgeMessage(new byte[]{1, 2}), readFrame(input));
                    assertArrayEquals(clientForgeMessage(new byte[]{2, 0}), readFrame(input));
                    writeFrame(output, serverForgeMessage(new byte[]{2, 0}));
                    assertArrayEquals(clientForgeAck(2), readFrame(input));
                    writeFrame(output, serverForgeMessage(new byte[]{3, 0, 0, 0}));
                    writeFrame(output, serverForgeAck(2));
                    assertArrayEquals(clientForgeAck(3), readFrame(input));
                    assertArrayEquals(clientForgeAck(4), readFrame(input));
                    writeFrame(output, serverForgeAck(3));
                    assertArrayEquals(clientForgeAck(5), readFrame(input));
                    sendJoinGame(output, 0, 200);
                    writeFrame(output, new byte[]{0x08});
                    newNegotiated.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newNegotiated.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                var bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    var input = new DataInputStream(client.getInputStream());
                    var output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    byte[] reset = readFrame(input);
                    assertEquals(0x3f, packetId(reset));
                    assertEquals((byte) 0xfe, reset[reset.length - 1]);
                    assertArrayEquals(serverForgeRegistration(), readFrame(input));
                    assertArrayEquals(serverForgeHello(7), readFrame(input));
                    writeFrame(output, clientForgeRegistration());
                    writeFrame(output, clientForgeMessage(new byte[]{1, 2}));
                    writeFrame(output, clientForgeMessage(new byte[]{2, 0}));
                    assertArrayEquals(serverForgeMessage(new byte[]{2, 0}), readFrame(input));
                    writeFrame(output, clientForgeAck(2));
                    assertArrayEquals(serverForgeMessage(new byte[]{3, 0, 0, 0}), readFrame(input));
                    assertArrayEquals(serverForgeAck(2), readFrame(input));
                    writeFrame(output, clientForgeAck(3));
                    writeFrame(output, clientForgeAck(4));
                    assertArrayEquals(serverForgeAck(3), readFrame(input));
                    writeFrame(output, clientForgeAck(5));
                    assertEquals(-1, respawnDimension(readFrame(input)));
                    assertEquals(7, respawnDimension(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    var result = transfer.get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.NETWORK_READY, result.status(), result.detail().orElse(""));
                    newNegotiated.get(5, TimeUnit.SECONDS);
                    oldClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void forgeTransferResetsHandshakeAndUsesServerHelloDimensionOverride() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        byte[] registration = serverForgeRegistration();
        byte[] largeRegistryPacket = serverForgeRegistryData();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newNegotiated = new CompletableFuture<Void>();
            var sendPosition = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    writeFrame(output, registration);
                    writeFrame(output, serverForgeHello(5));
                    writeFrame(output, serverForgeAck());
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    sendJoinGame(output, 0, 100);
                    sendRespawn(output, 7);
                    if (input.read() != -1) throw new AssertionError("old Forge backend received post-transfer data");
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    writeFrame(output, registration);
                    writeFrame(output, serverForgeHello(7));
                    writeFrame(output, largeRegistryPacket);
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    writeFrame(output, serverForgeAck());
                    sendJoinGame(output, 0, 200);
                    sendPosition.get(5, TimeUnit.SECONDS);
                    writeFrame(output, new byte[]{0x03, 0x44});
                    byte[] newWorldPosition = new byte[42];
                    newWorldPosition[0] = 0x06;
                    newWorldPosition[41] = 1;
                    assertArrayEquals(newWorldPosition, readFrame(input));
                    newNegotiated.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { newNegotiated.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertArrayEquals(registration, readFrame(input));
                    assertArrayEquals(serverForgeHello(5), readFrame(input));
                    assertArrayEquals(serverForgeAck(), readFrame(input));
                    writeFrame(output, clientForgeAck());
                    assertEquals(1, packetId(readFrame(input)));
                    assertEquals(7, respawnDimension(readFrame(input))); // Old backend changed dimension after login.
                    var player = awaitPlayer(listener);
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    byte[] reset = readFrame(input);
                    assertEquals(0x3f, packetId(reset));
                    assertEquals((byte) 0xfe, reset[reset.length - 1]);
                    assertArrayEquals(registration, readFrame(input));
                    assertArrayEquals(serverForgeHello(7), readFrame(input));
                    assertArrayEquals(largeRegistryPacket, readFrame(input));
                    assertThrows(TimeoutException.class, () -> transfer.get(100, TimeUnit.MILLISECONDS),
                            "a Forge ServerHello is not a completed backend handshake");
                    // A real client can keep sending its old-world position while FML resets.
                    // The replacement backend must see the handshake response first.
                    byte[] oldWorldPosition = new byte[42];
                    oldWorldPosition[0] = 0x06;
                    writeFrame(output, oldWorldPosition);
                    writeFrame(output, clientForgeAck());
                    assertArrayEquals(serverForgeAck(), readFrame(input));
                    assertEquals(-1, respawnDimension(readFrame(input))); // Force a world reload despite stale observation.
                    assertEquals(7, respawnDimension(readFrame(input))); // Target override, not Join Game's signed byte.
                    var result = transfer.get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.NETWORK_READY, result.status(), result.detail().orElse(""));
                    sendPosition.complete(null);
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(input));
                    byte[] newWorldPosition = new byte[42];
                    newWorldPosition[0] = 0x06;
                    newWorldPosition[41] = 1;
                    writeFrame(output, newWorldPosition);
                    newNegotiated.get(5, TimeUnit.SECONDS);
                    oldClosed.get(5, TimeUnit.SECONDS);
                }
            } finally {
                sendPosition.complete(null);
                listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void forgeRejectionAfterServerHelloFailsTransferAndReleasesSession() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newRejected = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    writeFrame(output, serverForgeHello(0));
                    writeFrame(output, serverForgeAck());
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    sendJoinGame(output, 0, 100);
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    writeFrame(output, serverForgeHello(0));
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    newRejected.complete(null);
                } catch (Throwable failure) { newRejected.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertArrayEquals(serverForgeHello(0), readFrame(input));
                    assertArrayEquals(serverForgeAck(), readFrame(input));
                    writeFrame(output, clientForgeAck());
                    assertEquals(1, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    byte[] reset = readFrame(input);
                    assertEquals(0x3f, packetId(reset));
                    assertEquals((byte) 0xfe, reset[reset.length - 1]);
                    assertArrayEquals(serverForgeHello(0), readFrame(input));
                    assertThrows(TimeoutException.class, () -> transfer.get(100, TimeUnit.MILLISECONDS));
                    writeFrame(output, clientForgeAck());
                    newRejected.get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                    assertEquals(-1, input.read());
                    oldClosed.get(5, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!listener.allSessions().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertTrue(listener.allSessions().isEmpty());
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void forgeToVanillaThenForgeResetsClientRegistryOnlyOnce() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server(); ServerSocket thirdServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var newRelayed = new CompletableFuture<Void>();
            var vanillaClosed = new CompletableFuture<Void>();
            var thirdNegotiated = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    writeFrame(output, serverForgeHello(5));
                    writeFrame(output, serverForgeAck());
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    sendJoinGame(output, 0, 100);
                    assertEquals(-1, input.read());
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0, 200);
                    assertArrayEquals(new byte[]{0x01, 0x33}, readFrame(input));
                    writeFrame(output, new byte[]{0x03, 0x44});
                    newRelayed.complete(null);
                    assertEquals(-1, input.read());
                    vanillaClosed.complete(null);
                } catch (Throwable failure) {
                    newRelayed.completeExceptionally(failure);
                    vanillaClosed.completeExceptionally(failure);
                }
            });
            backendThread(thirdServer, () -> {
                try (Socket socket = thirdServer.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    writeFrame(output, serverForgeHello(7));
                    writeFrame(output, serverForgeAck());
                    assertArrayEquals(clientForgeAck(), readFrame(input));
                    sendJoinGame(output, 0, 300);
                    thirdNegotiated.complete(null);
                    while (input.read() != -1) { }
                } catch (Throwable failure) { thirdNegotiated.completeExceptionally(failure); }
            });
            register(catalog, "old", oldServer);
            register(catalog, "new", newServer);
            register(catalog, "third", thirdServer);
            var listener = listener(catalog);
            try {
                int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress()).getPort();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    client.setSoTimeout(5000);
                    var input = new DataInputStream(client.getInputStream());
                    var output = new DataOutputStream(client.getOutputStream());
                    sendLogin(output);
                    assertEquals(2, packetId(readFrame(input)));
                    assertArrayEquals(serverForgeHello(5), readFrame(input));
                    assertArrayEquals(serverForgeAck(), readFrame(input));
                    writeFrame(output, clientForgeAck());
                    assertEquals(1, packetId(readFrame(input)));
                    var player = awaitPlayer(listener);
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(TransferStatus.NETWORK_READY, transfer.status(), transfer.detail().orElse(""));
                    byte[] reset = readFrame(input);
                    assertEquals(0x3f, packetId(reset));
                    assertEquals((byte) 0xfe, reset[reset.length - 1]);
                    assertEquals(-1, respawnDimension(readFrame(input)));
                    assertEquals(0, respawnDimension(readFrame(input)));
                    assertEquals(8, packetId(readFrame(input)));
                    writeFrame(output, new byte[]{0x01, 0x33});
                    assertArrayEquals(new byte[]{0x03, 0x44}, readFrame(input));
                    newRelayed.get(5, TimeUnit.SECONDS);
                    oldClosed.get(5, TimeUnit.SECONDS);

                    var thirdTransfer = listener.transfer(player.identity(), "third").toCompletableFuture();
                    assertArrayEquals(serverForgeHello(7), readFrame(input)); // Reset already left FML in HELLO.
                    assertArrayEquals(serverForgeAck(), readFrame(input));
                    writeFrame(output, clientForgeAck());
                    assertEquals(-1, respawnDimension(readFrame(input)));
                    assertEquals(7, respawnDimension(readFrame(input)));
                    assertEquals(TransferStatus.NETWORK_READY,
                            thirdTransfer.get(5, TimeUnit.SECONDS).status());
                    thirdNegotiated.get(5, TimeUnit.SECONDS);
                    vanillaClosed.get(5, TimeUnit.SECONDS);
                }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void clientDisconnectDuringCandidateLoginCompletesTransferAndReleasesBothReservations() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var candidateAccepted = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    if (input.read() != -1) throw new AssertionError("unexpected old backend data");
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    readFrame(input); readFrame(input);
                    candidateAccepted.complete(null);
                    if (input.read() != -1) throw new AssertionError("unexpected candidate data");
                } catch (Throwable failure) { candidateAccepted.completeExceptionally(failure); }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort());
                try {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    sendLogin(new DataOutputStream(client.getOutputStream()));
                    readFrame(input); readFrame(input); readFrame(input);
                    var player = awaitPlayer(listener);
                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    candidateAccepted.get(5, TimeUnit.SECONDS);
                    client.close();
                    assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                    oldClosed.get(5, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertTrue(listener.online().isEmpty());
                } finally { client.close(); }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void playerDisconnectWhileOldRelayIsPausedCompletesTransferAndReleasesBothReservations() throws Exception {
        var catalog = new InMemoryBackendCatalog();
        try (ServerSocket oldServer = server(); ServerSocket newServer = server()) {
            var oldClosed = new CompletableFuture<Void>();
            var candidateAccepted = new CompletableFuture<Void>();
            var candidateClosed = new CompletableFuture<Void>();
            backendThread(oldServer, () -> {
                try (Socket socket = oldServer.accept()) {
                    socket.setSoTimeout(30000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptLogin(input, output, 0);
                    if (!java.util.Arrays.equals(new byte[]{0x01, 0x55}, readFrame(input))) {
                        throw new AssertionError("held client frame did not drain to the old backend");
                    }
                    if (input.read() != -1) throw new AssertionError("unexpected old backend data");
                    oldClosed.complete(null);
                } catch (Throwable failure) { oldClosed.completeExceptionally(failure); }
            });
            backendThread(newServer, () -> {
                try (Socket socket = newServer.accept()) {
                    socket.setSoTimeout(30000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    acceptHandshakeAndLogin(input, output);
                    sendLoginSuccess(output);
                    sendJoinGame(output, 0, 200);
                    candidateAccepted.complete(null);
                    if (input.read() != -1) throw new AssertionError("unexpected candidate data");
                    candidateClosed.complete(null);
                } catch (Throwable failure) {
                    candidateAccepted.completeExceptionally(failure);
                    candidateClosed.completeExceptionally(failure);
                }
            });
            var oldHandle = register(catalog, "old", oldServer);
            var newHandle = register(catalog, "new", newServer);
            var listener = listener(catalog);
            try {
                InetSocketAddress bound = (InetSocketAddress) listener.start().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).localAddress();
                Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort());
                try {
                    client.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    sendLogin(new DataOutputStream(client.getOutputStream()));
                    readFrame(input); readFrame(input); readFrame(input);
                    var player = awaitPlayer(listener);

                    var session = listener.allSessions().iterator().next();
                    var backendField = Session.class.getDeclaredField("backend");
                    backendField.setAccessible(true);
                    Channel oldChannel = (Channel) backendField.get(session);
                    var frontendField = Session.class.getDeclaredField("frontend");
                    frontendField.setAccessible(true);
                    Channel frontend = (Channel) frontendField.get(session);
                    var heldWrite = new CompletableFuture<Void>();
                    var heldMessage = new AtomicReference<Object>();
                    var heldPromise = new AtomicReference<ChannelPromise>();
                    var heldContext = new AtomicReference<ChannelHandlerContext>();
                    oldChannel.eventLoop().submit(() -> oldChannel.pipeline().addFirst("hold-cutover-write",
                            new ChannelDuplexHandler() {
                                @Override public void write(ChannelHandlerContext ctx, Object message,
                                                            ChannelPromise promise) {
                                    heldContext.set(ctx);
                                    heldMessage.set(message);
                                    heldPromise.set(promise);
                                    heldWrite.complete(null);
                                }

                                @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    ReferenceCountUtil.release(heldMessage.getAndSet(null));
                                    ChannelPromise promise = heldPromise.getAndSet(null);
                                    if (promise != null) promise.tryFailure(
                                            new IllegalStateException("old backend closed"));
                                    super.channelInactive(ctx);
                                }
                            })).get(5, TimeUnit.SECONDS);
                    writeFrame(new DataOutputStream(client.getOutputStream()), new byte[]{0x01, 0x55});
                    heldWrite.get(5, TimeUnit.SECONDS);

                    var transfer = listener.transfer(player.identity(), "new").toCompletableFuture();
                    candidateAccepted.get(5, TimeUnit.SECONDS);
                    boolean buffersInstalled = false;
                    for (int i = 0; i < 200 && !buffersInstalled; i++) {
                        buffersInstalled = frontend.eventLoop().submit(() ->
                                frontend.pipeline().get("transfer-client-buffer") != null
                                        && oldChannel.pipeline().get("transfer-old-backend-buffer") != null)
                                .get(1, TimeUnit.SECONDS);
                        if (!buffersInstalled) Thread.sleep(5);
                    }
                    assertTrue(buffersInstalled, "cutover buffers should be installed while old write is held");

                    var disconnect = listener.disconnect(player.identity(), "转服期间断开");
                    oldChannel.eventLoop().submit(() -> heldContext.get().writeAndFlush(
                            heldMessage.getAndSet(null), heldPromise.getAndSet(null))).get(5, TimeUnit.SECONDS);
                    var kick = new ByteArrayInputStream(readFrame(input));
                    assertEquals(0x40, readVarInt(kick));
                    assertEquals("{\"text\":\"转服期间断开\"}",
                            new String(kick.readNBytes(readVarInt(kick)), StandardCharsets.UTF_8));
                    assertEquals(dev.strataproxy.api.DisconnectResult.DISCONNECTED,
                            disconnect.toCompletableFuture().get(5, TimeUnit.SECONDS));
                    assertEquals(TransferStatus.FAILED, transfer.get(5, TimeUnit.SECONDS).status());
                    oldClosed.get(5, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertTrue(listener.online().isEmpty());
                    candidateClosed.get(5, TimeUnit.SECONDS);
                } finally { client.close(); }
            } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    private static ProxySessionListener listener(InMemoryBackendCatalog catalog) {
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
        listener.setPlacement(ignored -> CompletableFuture.completedFuture(Optional.of(PlacementDecision.select("old"))));
        return listener;
    }

    private static dev.strataproxy.api.PlayerView awaitPlayer(ProxySessionListener listener) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (listener.online().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(1, listener.online().size());
        Thread.sleep(20); // Let PLAY frames observed by the proxy relay complete on its event loop.
        return listener.online().get(0);
    }

    private static BackendHandle register(InMemoryBackendCatalog catalog,
                                                              String name, ServerSocket socket) {
        return catalog.register(new BackendRegistration(new BackendId(name), new BackendOwner("static", 0),
                URI.create("tcp://127.0.0.1:" + socket.getLocalPort()), Map.of(), Map.of())).handle();
    }

    private static void acceptLogin(DataInputStream input, DataOutputStream output, int dimension) throws Exception {
        acceptLogin(input, output, dimension, 100);
    }

    private static void acceptLogin(DataInputStream input, DataOutputStream output, int dimension, int entityId)
            throws Exception {
        acceptHandshakeAndLogin(input, output);
        sendLoginSuccess(output);
        sendJoinGame(output, dimension, entityId);
        writeFrame(output, new byte[]{0x08});
    }

    private static void acceptHandshakeAndLogin(DataInputStream input, DataOutputStream output) throws Exception {
        assertEquals(0, packetId(readFrame(input)));
        assertEquals(0, packetId(readFrame(input)));
    }

    private static void sendLoginSuccess(DataOutputStream output) throws Exception {
        ByteArrayOutputStream success = new ByteArrayOutputStream();
        var successData = new DataOutputStream(success);
        writeVarInt(successData, 2);
        writeString(successData, PLAYER_ID.toString());
        writeString(successData, USERNAME);
        writeFrame(output, success.toByteArray());
    }

    private static void sendJoinGame(DataOutputStream output, int dimension, int entityId) throws Exception {
        ByteArrayOutputStream join = new ByteArrayOutputStream();
        var joinData = new DataOutputStream(join);
        writeVarInt(joinData, 1);
        joinData.writeInt(entityId);
        joinData.writeByte(0);
        joinData.writeByte(dimension);
        joinData.writeByte(1);
        joinData.writeByte(20);
        writeString(joinData, "default");
        writeFrame(output, join.toByteArray());
    }

    private static void sendRespawn(DataOutputStream output, int dimension) throws Exception {
        ByteArrayOutputStream respawn = new ByteArrayOutputStream();
        var data = new DataOutputStream(respawn);
        writeVarInt(data, 7);
        data.writeInt(dimension);
        data.writeByte(1);
        data.writeByte(0);
        writeString(data, "default");
        writeFrame(output, respawn.toByteArray());
    }

    private static int respawnDimension(byte[] packet) throws Exception {
        var input = new DataInputStream(new ByteArrayInputStream(packet));
        assertEquals(7, readVarInt(input));
        return input.readInt();
    }

    private static byte[] keepAlive(int id) {
        return new byte[]{0, (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id};
    }

    private static int keepAliveId(byte[] packet) throws Exception {
        var input = new DataInputStream(new ByteArrayInputStream(packet));
        assertEquals(0, readVarInt(input));
        int id = input.readInt();
        assertEquals(-1, input.read());
        return id;
    }

    private static byte[] serverForgeHello(int dimensionOverride) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        writeVarInt(output, 0x3f);
        writeString(output, "FML|HS");
        output.writeShort(6);
        output.writeByte(0);
        output.writeByte(2);
        output.writeInt(dimensionOverride);
        return payload.toByteArray();
    }

    private static byte[] serverForgeRegistration() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        // FMLHandshakeServerState.START sends this channel registration before ServerHello.
        byte[] channels = "FML|HS\0FML".getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, 0x3f);
        writeString(output, "REGISTER");
        output.writeShort(channels.length);
        output.write(channels);
        return payload.toByteArray();
    }

    private static byte[] clientForgeRegistration() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        byte[] channels = "FML|HS\0FML".getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, 0x17);
        writeString(output, "REGISTER");
        output.writeShort(channels.length);
        output.write(channels);
        return payload.toByteArray();
    }

    private static byte[] serverForgeMessage(byte[] handshake) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        writeVarInt(output, 0x3f);
        writeString(output, "FML|HS");
        output.writeShort(handshake.length);
        output.write(handshake);
        return payload.toByteArray();
    }

    private static byte[] clientForgeMessage(byte[] handshake) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        writeVarInt(output, 0x17);
        writeString(output, "FML|HS");
        output.writeShort(handshake.length);
        output.write(handshake);
        return payload.toByteArray();
    }

    private static byte[] serverForgeAck(int phase) throws Exception {
        return serverForgeMessage(new byte[]{(byte) 0xff, (byte) phase});
    }

    private static byte[] clientForgeAck(int phase) throws Exception {
        return clientForgeMessage(new byte[]{(byte) 0xff, (byte) phase});
    }

    private static byte[] serverForgeAck() throws Exception {
        return serverForgeAck(3);
    }

    private static byte[] serverForgeRegistryData() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        var output = new DataOutputStream(payload);
        writeVarInt(output, 0x3f);
        writeString(output, "FML|HS");
        output.writeShort(0x8000);
        output.writeByte(2); // 65,536-byte extended VarShort payload.
        output.writeByte(3); // ModIdData discriminator.
        output.write(new byte[65_535]);
        return payload.toByteArray();
    }

    private static byte[] clientForgeAck() throws Exception {
        return clientForgeAck(5);
    }

    private static void sendLogin(DataOutputStream output) throws Exception {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        var hello = new DataOutputStream(handshake);
        writeVarInt(hello, 0); writeVarInt(hello, 5); writeString(hello, "localhost");
        hello.writeShort(25565); writeVarInt(hello, 2);
        writeFrame(output, handshake.toByteArray());
        ByteArrayOutputStream login = new ByteArrayOutputStream();
        var start = new DataOutputStream(login);
        writeVarInt(start, 0); writeString(start, USERNAME);
        writeFrame(output, login.toByteArray());
    }

    private static ServerSocket server() throws Exception {
        return new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    private static void backendThread(ServerSocket socket, Runnable task) {
        Thread thread = new Thread(task, "transfer-test-backend-" + socket.getLocalPort());
        thread.setDaemon(true);
        thread.start();
    }

    private static int packetId(byte[] bytes) throws Exception {
        return readVarInt(new ByteArrayInputStream(bytes));
    }

    private static byte[] readFrame(DataInputStream input) throws Exception {
        int length = readVarInt(input);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return bytes;
    }

    private static int readVarInt(InputStream input) throws Exception {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("oversized VarInt");
    }

    private static void writeFrame(DataOutputStream output, byte[] bytes) throws Exception {
        writeVarInt(output, bytes.length);
        output.write(bytes);
        output.flush();
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeVarInt(DataOutputStream output, int value) throws Exception {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            output.writeByte(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }
}
