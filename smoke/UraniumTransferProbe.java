package dev.strataproxy.smoke;

import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.TransferResult;
import dev.strataproxy.api.TransferStatus;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;
import dev.strataproxy.core.backend.InMemoryBackendCatalog;
import dev.strataproxy.core.session.ProxySessionListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Disposable protocol-5 client exercising a real Forge-to-Forge backend transfer. */
public final class UraniumTransferProbe {
    private UraniumTransferProbe() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && arguments[0].equals("--external")) {
            runClient(Integer.parseInt(arguments[1]), null);
            return;
        }
        if (arguments.length != 2) throw new IllegalArgumentException("Expected old and new Uranium ports");
        int oldPort = Integer.parseInt(arguments[0]);
        int newPort = Integer.parseInt(arguments[1]);
        var catalog = new InMemoryBackendCatalog();
        var owner = new BackendOwner("uranium-smoke", 1);
        catalog.register(new BackendRegistration(new BackendId("old"), owner,
                URI.create("tcp://127.0.0.1:" + oldPort), 10, Map.of(), Map.of()));
        catalog.register(new BackendRegistration(new BackendId("new"), owner,
                URI.create("tcp://127.0.0.1:" + newPort), 10, Map.of(), Map.of()));
        var proxy = new ProxySessionListener(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), catalog);
        proxy.setPlacement(player -> CompletableFuture.completedFuture(Optional.empty()));
        try {
            var channel = proxy.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            int proxyPort = ((InetSocketAddress) channel.localAddress()).getPort();
            runClient(proxyPort, proxy);
        } finally {
            proxy.close().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
    }

    private static void runClient(int proxyPort, ProxySessionListener proxy) throws Exception {
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), proxyPort)) {
            client.setSoTimeout(30000);
            send(client, payload(out -> {
                out.writeByte(0);
                varInt(out, 5);
                string(out, "localhost");
                out.writeShort(proxyPort);
                varInt(out, 2);
            }));
            send(client, payload(out -> {
                out.writeByte(0);
                string(out, "NettyProbe");
            }));
            require(packetId(read(client)) == 2, "missing LOGIN success");

            int firstHellos = 0;
            while (true) {
                byte[] frame = read(client);
                int id = packetId(frame);
                if (id == 1) break;
                if (id == 0) send(client, frame);
                if (id == 0x40) throw new AssertionError("disconnected before initial Join Game");
                if (id == 0x3f && respondToForge(client, frame) == ForgeMessage.SERVER_HELLO) firstHellos++;
            }
            require(firstHellos == 1, "expected one initial Forge ServerHello; got " + firstHellos);
            PlayerView player = proxy == null ? null : awaitPlayer(proxy, "old");
            var transfer = proxy == null ? null : proxy.transfer(player.identity(), "new").toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int resets = 0;
            int secondHellos = 0;
            int respawns = 0;
            int keepAlivesAfterReady = 0;
            while (System.nanoTime() < deadline) {
                byte[] frame = read(client);
                int id = packetId(frame);
                if (id == 0x40) throw new AssertionError("disconnected during transfer");
                if (id == 0) {
                    send(client, frame);
                    if (transfer == null ? respawns >= 2 : transfer.isDone()) keepAlivesAfterReady++;
                }
                if (id == 0x3f) {
                    ForgeMessage message = respondToForge(client, frame);
                    if (message == ForgeMessage.RESET) resets++;
                    if (message == ForgeMessage.SERVER_HELLO) secondHellos++;
                }
                if (id == 7) respawns++;
                if ((transfer == null || transfer.isDone()) && respawns >= 2 && keepAlivesAfterReady >= 2) break;
            }
            if (transfer != null) {
                TransferResult result = transfer.get(2, TimeUnit.SECONDS);
                require(result.status() == TransferStatus.NETWORK_READY,
                        "transfer failed: " + result.status() + " " + result.detail().orElse(""));
            }
            require(resets == 1, "expected one Forge reset; got " + resets);
            require(secondHellos == 1, "expected one replacement Forge ServerHello; got " + secondHellos);
            require(respawns >= 2, "expected two world transition Respawns; got " + respawns);
            require(keepAlivesAfterReady >= 2,
                    "replacement session did not sustain two Keep Alives; got " + keepAlivesAfterReady);
            if (proxy != null) {
                require(proxy.find(player.identity()).flatMap(PlayerView::currentServer).orElse("").equals("new"),
                        "player did not move to new backend");
            }
            System.out.printf("REAL_URANIUM_TRANSFER_PASS mode=%s reset=%d serverHellos=%d respawns=%d "
                            + "keepAlivesAfterReady=%d%n",
                    proxy == null ? "plugin" : "direct", resets, secondHellos, respawns, keepAlivesAfterReady);
        }
    }

    private static PlayerView awaitPlayer(ProxySessionListener proxy, String backend) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (PlayerView player : proxy.online()) {
                if (player.currentServer().filter(backend::equals).isPresent()) return player;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("player was not published on " + backend);
    }

    private enum ForgeMessage { OTHER, SERVER_HELLO, RESET }

    private static ForgeMessage respondToForge(Socket socket, byte[] frame) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(frame));
        require(varInt(input) == 0x3f, "not a server custom payload");
        int channelLength = varInt(input);
        require(channelLength >= 1 && channelLength <= 20, "invalid custom channel length");
        byte[] channelBytes = new byte[channelLength];
        input.readFully(channelBytes);
        String channel = new String(channelBytes, StandardCharsets.UTF_8);
        int dataLength = input.readUnsignedShort();
        if ((dataLength & 0x8000) != 0) dataLength = (dataLength & 0x7fff) | (input.readUnsignedByte() << 15);
        require(dataLength >= 1 && dataLength <= 2_097_152, "invalid custom payload length");
        byte[] data = new byte[dataLength];
        input.readFully(data);
        if (!channel.equals("FML|HS")) return ForgeMessage.OTHER;
        switch (data[0] & 255) {
            case 0 -> {
                custom(socket, "REGISTER", "FML|HS\0FML".getBytes(StandardCharsets.UTF_8));
                custom(socket, "FML|HS", new byte[]{1, 2});
                custom(socket, "FML|HS", new byte[]{2, 0});
                return ForgeMessage.SERVER_HELLO;
            }
            case 2 -> custom(socket, "FML|HS", new byte[]{-1, 2});
            case 3 -> custom(socket, "FML|HS", new byte[]{-1, 3});
            case 255 -> {
                require(data.length >= 2, "short Forge acknowledgement");
                custom(socket, "FML|HS", new byte[]{-1, (byte) (data[1] == 2 ? 4 : 5)});
            }
            case 254 -> { return ForgeMessage.RESET; }
            default -> throw new AssertionError("unexpected Forge handshake discriminator " + (data[0] & 255));
        }
        return ForgeMessage.OTHER;
    }

    private static void custom(Socket socket, String channel, byte[] data) throws IOException {
        send(socket, payload(out -> {
            out.writeByte(0x17);
            string(out, channel);
            out.writeShort(data.length);
            out.write(data);
        }));
    }

    private interface Payload { void write(DataOutputStream output) throws IOException; }

    private static byte[] payload(Payload writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        writer.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varInt(output, bytes.length);
        output.write(bytes);
    }

    private static void varInt(DataOutputStream output, int value) throws IOException {
        while (value > 127) {
            output.writeByte((value & 127) | 128);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    private static int varInt(DataInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.readUnsignedByte();
            value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("invalid VarInt");
    }

    private static void send(Socket socket, byte[] frame) throws IOException {
        byte[] header = payload(out -> varInt(out, frame.length));
        socket.getOutputStream().write(header);
        socket.getOutputStream().write(frame);
    }

    private static byte[] read(Socket socket) throws IOException {
        var input = new DataInputStream(socket.getInputStream());
        int length = varInt(input);
        require(length >= 1 && length <= 2_097_152, "invalid frame length " + length);
        byte[] frame = new byte[length];
        input.readFully(frame);
        return frame;
    }

    private static int packetId(byte[] frame) throws IOException {
        return varInt(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
