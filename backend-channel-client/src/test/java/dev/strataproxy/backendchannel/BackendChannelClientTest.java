package dev.strataproxy.backendchannel;

import dev.strataproxy.messaging.Endpoint;
import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.MessageKind;
import dev.strataproxy.messaging.MessageChannel;
import dev.strataproxy.messaging.protocol.MessageCodec;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BackendChannelClientTest {
    @Test void messageCodecRoundTripsAndRejectsMalformedFrames() throws Exception {
        Message message = Message.request("demo:echo", Endpoint.backend("island-a"), Endpoint.proxy(),
                new byte[] { 1, 2, 3 });
        byte[] frame = MessageCodec.message(9L, 1000L, message);
        MessageCodec.IncomingMessage decoded = MessageCodec.decodeMessage(frame);
        assertEquals(9L, decoded.operationId);
        assertEquals(1000L, decoded.timeoutMillis);
        assertEquals(message.id(), decoded.message.id());
        assertArrayEquals(message.payload(), decoded.message.payload());

        byte[] trailing = Arrays.copyOf(frame, frame.length + 1);
        assertThrows(java.io.IOException.class, () -> MessageCodec.decodeMessage(trailing));
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.message(0L, 1000L, message));
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.message(1L, 1000L,
                Message.reply(message, Endpoint.proxy(), new byte[0])));
    }

    @Test void authenticatesAndUsesUnifiedMessagingInBothDirections() throws Exception {
        final byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x6a);
        final ServerSocket server = new ServerSocket(0);
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                byte[] nonce = new byte[Wire.NONCE_BYTES];
                Arrays.fill(nonce, (byte) 0x33);
                FrameCodec.write(out, Wire.hello(Wire.VERSION, nonce));
                Wire.Registration registration = Wire.decodeRegister(FrameCodec.read(in));
                assertEquals("backend-1", registration.instanceId);
                byte[] canonical = Wire.canonicalRegistration("backend-1", "island-a", "tcp://127.0.0.1:25565", "gen-1", "key-1");
                byte[] signed = new byte[nonce.length + canonical.length];
                System.arraycopy(nonce, 0, signed, 0, nonce.length);
                System.arraycopy(canonical, 0, signed, nonce.length, canonical.length);
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                assertArrayEquals(mac.doFinal(signed), registration.signature);
                FrameCodec.write(out, Wire.registered(77L));

                MessageCodec.IncomingMessage outgoing = MessageCodec.decodeMessage(FrameCodec.read(in));
                assertEquals(MessageKind.REQUEST, outgoing.message.kind());
                assertEquals(Endpoint.backend("island-a"), outgoing.message.source());
                assertEquals(Endpoint.proxy(), outgoing.message.target());
                Message outgoingReply = Message.reply(outgoing.message, Endpoint.proxy(), "answer".getBytes(StandardCharsets.UTF_8));
                FrameCodec.write(out, MessageCodec.reply(outgoing.operationId, outgoingReply));

                Message inbound = Message.request("backend:echo", Endpoint.proxy(), Endpoint.backend("island-a"),
                        "hello".getBytes(StandardCharsets.UTF_8));
                FrameCodec.write(out, MessageCodec.message(42L, 2000L, inbound));
                MessageCodec.Response response = MessageCodec.decodeResponse(FrameCodec.read(in));
                assertEquals(42L, response.operationId);
                assertEquals(MessageCodec.Response.Type.REPLY, response.type);
                assertEquals(inbound.id(), response.message.replyTo());
                assertArrayEquals("hello!".getBytes(StandardCharsets.UTF_8), response.message.payload());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });

        BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(), "backend-1", "island-a",
                "tcp://127.0.0.1:25565", "gen-1", "key-1", secret, 10_000L, 100L, 500L);
        dev.strataproxy.messaging.Messaging messaging = client.messaging("test-plugin");
        MessageChannel channel = messaging.channel("backend:echo");
        channel.onRequest(message -> CompletableFuture.completedFuture(
                (new String(message.payload(), StandardCharsets.UTF_8) + "!").getBytes(StandardCharsets.UTF_8)));
        try {
            assertTrue(client.awaitRegistered(5, TimeUnit.SECONDS));
            assertEquals(77L, client.getEpoch());
            Message answer = messaging.channel("proxy:query").request(Endpoint.proxy(), new byte[] { 7 })
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertArrayEquals("answer".getBytes(StandardCharsets.UTF_8), answer.payload());
            peer.get(5, TimeUnit.SECONDS);
        } finally {
            messaging.close();
            client.close();
        }
    }

    @Test void frameCodecRejectsOversizeLength() throws Exception {
        assertThrows(java.io.IOException.class, () -> FrameCodec.read(new DataInputStream(
                new java.io.ByteArrayInputStream(new byte[] { 0, 1, 0, 1 }))));
    }

    @Test void slowPeerDoesNotBlockSendCaller() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x29);
        ServerSocket server = new ServerSocket(0);
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                byte[] nonce = new byte[Wire.NONCE_BYTES];
                FrameCodec.write(out, Wire.hello(Wire.VERSION, nonce));
                Wire.decodeRegister(FrameCodec.read(in));
                FrameCodec.write(out, Wire.registered(1L));
                Thread.sleep(1200L); // Do not read messaging frames while the caller submits them.
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });
        BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(), "slow-peer", "island-slow",
                "tcp://127.0.0.1:25570", "gen-slow", "key-1", secret);
        dev.strataproxy.messaging.Messaging messaging = client.messaging("slow-test");
        try {
            assertTrue(client.awaitRegistered(5, TimeUnit.SECONDS));
            byte[] payload = new byte[Message.MAX_PAYLOAD_BYTES];
            long started = System.nanoTime();
            for (int i = 0; i < 24; i++) messaging.channel("test:slow").send(Endpoint.proxy(), payload);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 1000L, "send API must not wait for the peer socket write");
        } finally {
            messaging.close();
            client.close();
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void neverCompletingHandlersExpireAndReleaseInboundCapacity() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x35);
        ServerSocket server = new ServerSocket(0);
        CountDownLatch handlerInstalled = new CountDownLatch(1);
        CountDownLatch firstBatchDispatched = new CountDownLatch(128);
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                FrameCodec.write(out, Wire.hello(Wire.VERSION, new byte[Wire.NONCE_BYTES]));
                Wire.decodeRegister(FrameCodec.read(in));
                FrameCodec.write(out, Wire.registered(1L));
                if (!handlerInstalled.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("handler was not installed");

                for (int i = 1; i <= 128; i++) {
                    Message request = Message.request("test:hang", Endpoint.proxy(), Endpoint.backend("island-hang"), new byte[0]);
                    FrameCodec.write(out, MessageCodec.message(i, 1000L, request));
                }
                if (!firstBatchDispatched.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("request handlers were not dispatched");
                Thread.sleep(1200L); // Let all incomplete handlers time out and release their bounded slots.
                Message last = Message.request("test:hang", Endpoint.proxy(), Endpoint.backend("island-hang"), new byte[0]);
                FrameCodec.write(out, MessageCodec.message(129L, 3000L, last));
                MessageCodec.Response afterExpiry;
                do {
                    afterExpiry = MessageCodec.decodeResponse(FrameCodec.read(in));
                    if (afterExpiry.operationId != 129L) {
                        assertTrue(afterExpiry.operationId >= 1L && afterExpiry.operationId <= 128L);
                        assertEquals(MessageCodec.Response.Type.ERROR, afterExpiry.type);
                        assertEquals(dev.strataproxy.messaging.MessagingException.Code.TIMED_OUT, afterExpiry.errorCode);
                    }
                } while (afterExpiry.operationId != 129L);
                assertEquals(MessageCodec.Response.Type.REPLY, afterExpiry.type);
                assertArrayEquals(new byte[] { 1 }, afterExpiry.message.payload());
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });
        BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(), "hang-backend", "island-hang",
                "tcp://127.0.0.1:25571", "gen-hang", "key-1", secret);
        dev.strataproxy.messaging.Messaging messaging = client.messaging("hang-test");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        messaging.channel("test:hang").onRequest(message -> {
            int call = calls.incrementAndGet();
            if (call <= 128) {
                firstBatchDispatched.countDown();
                return new CompletableFuture<byte[]>();
            }
            return CompletableFuture.completedFuture(new byte[] { 1 });
        });
        handlerInstalled.countDown();
        try {
            assertTrue(client.awaitRegistered(5, TimeUnit.SECONDS));
            peer.get(10, TimeUnit.SECONDS);
        } finally {
            messaging.close();
            client.close();
        }
    }

    @Test void reconnectFailsOldOperationAndUsesOnlyNewSessionResponse() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x48);
        ServerSocket server = new ServerSocket(0);
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try {
                try (Socket first = server.accept()) {
                    DataInputStream in = new DataInputStream(first.getInputStream());
                    DataOutputStream out = new DataOutputStream(first.getOutputStream());
                    FrameCodec.write(out, Wire.hello(Wire.VERSION, new byte[Wire.NONCE_BYTES]));
                    Wire.decodeRegister(FrameCodec.read(in));
                    FrameCodec.write(out, Wire.registered(1L));
                    MessageCodec.IncomingMessage old = MessageCodec.decodeMessage(FrameCodec.read(in));
                    assertEquals(1L, old.operationId);
                    // Closing this session must fail its in-flight operation before a new session is installed.
                }
                try (Socket second = server.accept()) {
                    DataInputStream in = new DataInputStream(second.getInputStream());
                    DataOutputStream out = new DataOutputStream(second.getOutputStream());
                    FrameCodec.write(out, Wire.hello(Wire.VERSION, new byte[Wire.NONCE_BYTES]));
                    Wire.decodeRegister(FrameCodec.read(in));
                    FrameCodec.write(out, Wire.registered(2L));
                    MessageCodec.IncomingMessage current = MessageCodec.decodeMessage(FrameCodec.read(in));
                    assertEquals(1L, current.operationId, "operation IDs are scoped to the new socket session");
                    Message reply = Message.reply(current.message, Endpoint.proxy(), new byte[] { 2 });
                    FrameCodec.write(out, MessageCodec.reply(current.operationId, reply));
                }
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });
        BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(), "reconnect-backend", "island-reconnect",
                "tcp://127.0.0.1:25572", "gen-reconnect", "key-1", secret, 10_000L, 25L, 100L);
        dev.strataproxy.messaging.Messaging messaging = client.messaging("reconnect-test");
        try {
            assertTrue(client.awaitRegistered(5, TimeUnit.SECONDS));
            CompletableFuture<Message> old = messaging.channel("test:reconnect")
                    .request(Endpoint.proxy(), new byte[] { 1 }).toCompletableFuture();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((client.getEpoch() != 2L || !client.isRegistered()) && System.nanoTime() < deadline) Thread.sleep(10L);
            assertEquals(2L, client.getEpoch());
            Message fresh = messaging.channel("test:reconnect").request(Endpoint.proxy(), new byte[] { 2 })
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 2 }, fresh.payload());
            assertTrue(old.isCompletedExceptionally());
            peer.get(5, TimeUnit.SECONDS);
        } finally {
            messaging.close();
            client.close();
        }
    }

    @Test void immediateCloseAfterRegistrationAlwaysFlushesGoodbye() throws Exception {
        final int iterations = 20;
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x71);
        ServerSocket server = new ServerSocket(0);
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    try (Socket socket = server.accept()) {
                        DataInputStream in = new DataInputStream(socket.getInputStream());
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        FrameCodec.write(out, Wire.hello(Wire.VERSION, new byte[Wire.NONCE_BYTES]));
                        Wire.decodeRegister(FrameCodec.read(in));
                        FrameCodec.write(out, Wire.registered(i + 1L));
                        byte[] goodbye = FrameCodec.read(in);
                        assertEquals(Wire.GOODBYE, goodbye[0] & 0xff);
                        Wire.validateEmpty(goodbye, Wire.GOODBYE);
                    }
                }
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });

        for (int i = 0; i < iterations; i++) {
            BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(),
                    "close-backend-" + i, "island-close", "tcp://127.0.0.1:25580", "gen-close", "key-1", secret);
            assertTrue(client.awaitRegistered(5, TimeUnit.SECONDS));
            client.close();
        }
        peer.get(10, TimeUnit.SECONDS);
    }
}
