package dev.moonbridge.clientws.net;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

/** Stand-in for the vanilla NetworkManager pipeline: same handler names, pass-through except packet_handler. */
final class VanillaStub extends ChannelDuplexHandler {

    static final String[] NAMES = { "timeout", "splitter", "decoder", "prepender", "encoder" };

    private final List<byte[]> writeOnActive;
    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    final List<Throwable> exceptions = new CopyOnWriteArrayList<Throwable>();
    final CountDownLatch inactive = new CountDownLatch(1);
    private int expected = -1;
    private CountDownLatch receivedLatch = new CountDownLatch(1);

    VanillaStub(List<byte[]> writeOnActive) {
        this.writeOnActive = writeOnActive;
    }

    static VanillaStub installInto(ChannelPipeline p, List<byte[]> writeOnActive) {
        for (String name : NAMES) {
            p.addLast(name, new ChannelDuplexHandler());
        }
        VanillaStub stub = new VanillaStub(writeOnActive);
        p.addLast("packet_handler", stub);
        return stub;
    }

    synchronized void expectBytes(int count) {
        expected = count;
        receivedLatch = new CountDownLatch(1);
        if (received.size() >= expected) {
            receivedLatch.countDown();
        }
    }

    byte[] awaitBytes(int count) throws InterruptedException {
        expectBytes(count);
        if (!receivedLatch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("received only " + received.size() + " of " + count + " bytes");
        }
        synchronized (this) {
            return received.toByteArray();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Vanilla writes its handshake packet as soon as the connection is up, before the WebSocket upgrade finished.
        for (byte[] b : writeOnActive) {
            ctx.write(Unpooled.wrappedBuffer(b));
        }
        ctx.flush();
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        synchronized (this) {
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            received.write(b, 0, b.length);
            if (expected >= 0 && received.size() >= expected) {
                receivedLatch.countDown();
            }
        }
        buf.release();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        exceptions.add(cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        inactive.countDown();
    }
}
