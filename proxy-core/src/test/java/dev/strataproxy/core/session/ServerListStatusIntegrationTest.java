package dev.strataproxy.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import dev.strataproxy.core.protocol.MinecraftHandshake;
import dev.strataproxy.core.protocol.ProtocolProfile;
import dev.strataproxy.core.protocol.ProtocolVarInt;
import dev.strataproxy.core.protocol.ServerListStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ServerListStatusIntegrationTest {
    @Test
    void configuredStatusAndPingUseTheListenerPipeline() throws Exception {
        var listener = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new InMemoryBackendCatalog());
        listener.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        String motd = "空岛 \"测试\"\\\n第二行 🌍";
        listener.setServerListStatus(new ServerListStatus(motd, 250, null));
        try {
            int port = ((InetSocketAddress) listener.start().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).localAddress()).getPort();
            assertThrows(IllegalStateException.class,
                    () -> listener.setServerListStatus(ServerListStatus.defaultStatus()));
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(5000);
                OutputStream output = client.getOutputStream();
                writeFrame(output, new MinecraftHandshake(5, "localhost", port, MinecraftHandshake.NextState.STATUS)
                        .encode(UnpooledByteBufAllocator.DEFAULT, ProtocolProfile.minecraft1710()));
                writeFrame(output, Unpooled.buffer(1).writeByte(0));
                var input = new DataInputStream(client.getInputStream());
                ByteBuf response = readFrame(input);
                try {
                    assertEquals(0, ProtocolVarInt.read(response));
                    int jsonLength = ProtocolVarInt.read(response);
                    var json = new ObjectMapper().readTree(response.readCharSequence(jsonLength, StandardCharsets.UTF_8).toString());
                    assertEquals(motd, json.path("description").path("text").textValue());
                    assertEquals(250, json.path("players").path("max").intValue());
                    assertEquals(0, json.path("players").path("online").intValue());
                    assertEquals(5, json.path("version").path("protocol").intValue());
                    assertEquals(0, response.readableBytes());
                } finally { response.release(); }
                long nonce = 0x123456789abcdef0L;
                writeFrame(output, Unpooled.buffer(9).writeByte(1).writeLong(nonce));
                ByteBuf pong = readFrame(input);
                try {
                    assertEquals(1, ProtocolVarInt.read(pong));
                    assertEquals(nonce, pong.readLong());
                    assertEquals(0, pong.readableBytes());
                } finally { pong.release(); }
                assertEquals(-1, input.read());
            }
        } finally { listener.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
    }

    private static void writeFrame(OutputStream output, ByteBuf body) throws Exception {
        try {
            int remaining = body.readableBytes();
            while ((remaining & ~0x7f) != 0) {
                output.write((remaining & 0x7f) | 0x80);
                remaining >>>= 7;
            }
            output.write(remaining);
            body.readBytes(output, body.readableBytes());
            output.flush();
        } finally { body.release(); }
    }

    private static ByteBuf readFrame(DataInputStream input) throws Exception {
        int length = 0;
        for (int shift = 0; shift < 21; shift += 7) {
            int value = input.readUnsignedByte();
            length |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                byte[] body = new byte[length];
                input.readFully(body);
                return Unpooled.wrappedBuffer(body);
            }
        }
        throw new AssertionError("invalid status frame length");
    }
}
