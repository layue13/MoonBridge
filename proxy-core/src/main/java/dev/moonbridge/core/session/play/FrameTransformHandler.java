package dev.moonbridge.core.session.play;

import dev.moonbridge.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * An inbound PLAY handler that sees one complete frame at a time and owns its reference counting, so
 * subclasses never call {@code release()}: {@link #transform} borrows the frame and answers what to forward.
 */
public abstract class FrameTransformHandler extends ChannelInboundHandlerAdapter {
    /** Returned by {@link #transform} when the frame was consumed and the relay resumes on its own later. */
    public static final ByteBuf CONSUMED = Unpooled.EMPTY_BUFFER;

    private final Runnable closeSession;

    public FrameTransformHandler(Runnable closeSession) {
        this.closeSession = closeSession;
    }

    /**
     * Borrows {@code frame}. Return {@code frame} itself to forward it unchanged, a different buffer to
     * forward instead (this class releases {@code frame}; the buffer must not be reachable from it),
     * {@code null} to drop the frame and keep the relay reading, or {@link #CONSUMED}. A buffer the method
     * allocates must be released by the method if it then throws; any exception closes the session.
     */
    protected abstract ByteBuf transform(ChannelHandlerContext ctx, ByteBuf frame);

    @Override public final void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf frame)) {
            ReferenceCountUtil.release(message);
            closeSession.run();
            return;
        }
        ByteBuf forward;
        try {
            forward = transform(ctx, frame);
        } catch (RuntimeException malformed) {
            frame.release();
            closeSession.run();
            return;
        }
        if (forward == frame) {
            ctx.fireChannelRead(frame);
            return;
        }
        frame.release();
        if (forward == null) RawRelay.continueAfterDrop(ctx.channel());
        else if (forward != CONSUMED) ctx.fireChannelRead(forward);
    }
}
