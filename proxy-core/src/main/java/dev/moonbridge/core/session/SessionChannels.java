package dev.moonbridge.core.session;

import dev.moonbridge.core.protocol.MinecraftFrameDecoder;
import dev.moonbridge.core.protocol.ProtocolProfile;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.function.Consumer;

/** Backend dialing and pipeline lookups shared by initial login and transfer. */
final class SessionChannels {
    static final String FRAME_DECODER = "minecraft-frame-decoder";
    static final String ENCRYPTED_FRAME_DECODER = "encrypted-frame-decoder";
    static final String FRAME_ENCODER = "minecraft-frame-encoder";

    private SessionChannels() { }

    static boolean isTcpAddress(URI address) {
        return "tcp".equalsIgnoreCase(address.getScheme()) && address.getHost() != null
                && address.getPort() >= 1 && address.getPort() <= 65535;
    }

    static InetSocketAddress socketAddress(URI address) {
        String host = address.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        var numericAddress = NetUtil.createInetAddressFromIpAddressString(host);
        if (numericAddress != null) {
            return new InetSocketAddress(numericAddress, address.getPort());
        }
        return InetSocketAddress.createUnresolved(host, address.getPort());
    }

    /** Dials on the player's event loop so session state stays confined to one thread. */
    static Bootstrap backendBootstrap(Channel frontend, AddressResolverGroup<InetSocketAddress> resolver,
                                      int connectTimeoutMillis, boolean autoRead,
                                      Consumer<ChannelPipeline> extraHandlers) {
        return new Bootstrap().group(frontend.eventLoop()).channel(NioSocketChannel.class)
                .resolver(resolver)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.AUTO_READ, autoRead)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        if (channel.eventLoop() != frontend.eventLoop()) {
                            throw new IllegalStateException("backend channel must share the player session event loop");
                        }
                        installCodecs(channel.pipeline());
                        extraHandlers.accept(channel.pipeline());
                    }
                });
    }

    static void installCodecs(ChannelPipeline pipeline) {
        pipeline.addLast(FRAME_DECODER, new MinecraftFrameDecoder(ProtocolProfile.minecraft1710(), true));
        pipeline.addLast(FRAME_ENCODER, new SessionFrameEncoder());
    }

    /** Name of the active frame decoder: the encrypted one replaces the plain one in online mode. */
    static String frameDecoderName(ChannelPipeline pipeline) {
        if (pipeline.get(ENCRYPTED_FRAME_DECODER) != null) return ENCRYPTED_FRAME_DECODER;
        if (pipeline.get(FRAME_DECODER) != null) return FRAME_DECODER;
        return null;
    }

    static MinecraftFrameDecoder frameDecoder(ChannelPipeline pipeline, String failure) {
        String name = frameDecoderName(pipeline);
        if (name == null || !(pipeline.get(name) instanceof MinecraftFrameDecoder decoder)) {
            throw new IllegalStateException(failure);
        }
        return decoder;
    }
}
