package dev.strataproxy.backendchannel;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BackendChannelClientTest {
    @Test void frameCodecRoundTripsAndRejectsOversize() throws Exception {
        byte[] frame = Wire.message("demo:echo", 9L, new byte[] { 1, 2, 3 });
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        FrameCodec.write(out, frame);
        byte[] encoded = bytes.toByteArray();
        assertEquals(frame.length, ByteBuffer.wrap(encoded).getInt());
        Wire.Message decoded = Wire.decodeMessage(FrameCodec.read(new DataInputStream(new java.io.ByteArrayInputStream(encoded))));
        assertEquals("demo:echo", decoded.channel);
        assertEquals(9L, decoded.requestId);
        assertArrayEquals(new byte[] { 1, 2, 3 }, decoded.payload);
        assertThrows(java.io.IOException.class, () -> FrameCodec.read(new DataInputStream(new java.io.ByteArrayInputStream(new byte[] { 0, 1, 0, 1 }))));
    }

    @Test void authenticatesRegistersAndExchangesRequestsInBothDirections() throws Exception {
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

                Wire.Message request = Wire.decodeMessage(FrameCodec.read(in));
                assertEquals("proxy:query", request.channel);
                assertEquals(1L, request.requestId);
                FrameCodec.write(out, Wire.response(request.requestId, Wire.STATUS_OK, "answer".getBytes(StandardCharsets.UTF_8)));

                FrameCodec.write(out, Wire.message("backend:echo", 42L, "hello".getBytes(StandardCharsets.UTF_8)));
                Wire.Response response = Wire.decodeResponse(FrameCodec.read(in));
                assertEquals(42L, response.requestId);
                assertEquals(Wire.STATUS_OK, response.status);
                assertArrayEquals("hello!".getBytes(StandardCharsets.UTF_8), response.payload);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });

        BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(), "backend-1", "island-a",
                "tcp://127.0.0.1:25565", "gen-1", "key-1", secret, 10_000L, 2_000L, 100L, 500L);
        client.registerHandler("backend:echo", payload -> CompletableFuture.completedFuture(
                (new String(payload, StandardCharsets.UTF_8) + "!").getBytes(StandardCharsets.UTF_8)));
        try {
            assertTrue(client.awaitRegistered(5, TimeUnit.SECONDS));
            assertEquals(77L, client.getEpoch());
            byte[] answer = client.request("proxy:query", new byte[] { 7 }).get(5, TimeUnit.SECONDS);
            assertArrayEquals("answer".getBytes(StandardCharsets.UTF_8), answer);
            peer.get(5, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    @Test void reconnectsAndRegistersAgainAfterConnectionLoss() throws Exception {
        final byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x2d);
        final ServerSocket server = new ServerSocket(0);
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try {
                for (int connection = 1; connection <= 2; connection++) {
                    try (Socket socket = server.accept()) {
                        DataInputStream in = new DataInputStream(socket.getInputStream());
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        byte[] nonce = new byte[Wire.NONCE_BYTES];
                        Arrays.fill(nonce, (byte) connection);
                        FrameCodec.write(out, Wire.hello(Wire.VERSION, nonce));
                        Wire.Registration registration = Wire.decodeRegister(FrameCodec.read(in));
                        assertEquals("retry-backend", registration.instanceId);
                        FrameCodec.write(out, Wire.registered(connection));
                        if (connection == 2) {
                            Wire.Message request = Wire.decodeMessage(FrameCodec.read(in));
                            FrameCodec.write(out, Wire.response(request.requestId, Wire.STATUS_OK, request.payload));
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });

        BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(), "retry-backend", "island-b",
                "tcp://127.0.0.1:25566", "gen-retry", "key-1", secret, 10_000L, 2_000L, 100L, 500L);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.getEpoch() < 2L && System.nanoTime() < deadline) Thread.sleep(10L);
            assertEquals(2L, client.getEpoch());
            assertTrue(client.isRegistered());
            byte[] payload = new byte[] { 8, 6, 7 };
            assertArrayEquals(payload, client.request("proxy:retry", payload).get(5, TimeUnit.SECONDS));
            peer.get(5, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    @Test void disconnectsWhenProxyStopsSendingPongsAndFailsPendingRequest() throws Exception {
        final byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x44);
        final ServerSocket server = new ServerSocket(0);
        CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                byte[] nonce = new byte[Wire.NONCE_BYTES];
                Arrays.fill(nonce, (byte) 0x61);
                FrameCodec.write(out, Wire.hello(Wire.VERSION, nonce));
                Wire.decodeRegister(FrameCodec.read(in));
                FrameCodec.write(out, Wire.registered(1L));
                Wire.decodeMessage(FrameCodec.read(in));
                while (true) FrameCodec.read(in); // Deliberately consume heartbeats without replying PONG.
            } catch (Exception ignored) {
                // The client should close this socket after its PONG watchdog expires.
            } finally {
                try { server.close(); } catch (Exception ignored) { }
            }
        });

        BackendChannelClient client = new BackendChannelClient("127.0.0.1", server.getLocalPort(), "silent-backend", "island-c",
                "tcp://127.0.0.1:25567", "gen-silent", "key-1", secret, 1_000L, 60_000L, 100L, 500L);
        try {
            assertTrue(client.awaitRegistered(5, TimeUnit.SECONDS));
            CompletableFuture<byte[]> pending = client.request("proxy:slow", new byte[] { 1 }, 60_000L, TimeUnit.MILLISECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (client.isRegistered() && System.nanoTime() < deadline) Thread.sleep(25L);
            assertFalse(client.isRegistered(), "client must expire a connection without PONGs");
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            peer.get(2, TimeUnit.SECONDS);
        } finally {
            client.close();
            server.close();
        }
    }
}
