package dev.moonbridge.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails a test class when it leaks a reference-counted buffer. Production runs with Netty leak
 * detection off, so this gate (PARANOID: every buffer is tracked) is where refcount mistakes in the
 * hand-written retain/release paths must surface. Auto-registered for every test class.
 */
public final class LeakGate implements BeforeAllCallback, AfterAllCallback {
    private static final String DETECTOR = "io.netty.util.ResourceLeakDetector";
    private static ListAppender<ILoggingEvent> appender;

    @Override public void beforeAll(ExtensionContext context) {
        install();
    }

    @Override public void afterAll(ExtensionContext context) {
        List<String> leaks = drainLeaks();
        if (!leaks.isEmpty()) {
            throw new AssertionError(leaks.size() + " leaked buffer report(s) after " + context.getDisplayName()
                    + ":\n" + String.join("\n---\n", leaks));
        }
    }

    private static synchronized void install() {
        if (appender != null) return;
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        Logger logger = (Logger) LoggerFactory.getLogger(DETECTOR);
        logger.setLevel(Level.ERROR);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** Forces GC, makes the detector inspect its queue, and returns (and clears) the leak reports found. */
    public static synchronized List<String> drainLeaks() {
        install();
        List<String> found = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            System.gc();
            try { Thread.sleep(40); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            // Every tracked allocation polls the detector's reference queue, so allocate from each allocator.
            release(UnpooledByteBufAllocator.DEFAULT.heapBuffer(1));
            release(UnpooledByteBufAllocator.DEFAULT.directBuffer(1));
            release(PooledByteBufAllocator.DEFAULT.directBuffer(1));
            release(io.netty.buffer.ByteBufAllocator.DEFAULT.directBuffer(1));
            synchronized (appender.list) {
                for (ILoggingEvent event : appender.list) {
                    String message = event.getFormattedMessage();
                    if (message.startsWith("LEAK:")) found.add(message);
                }
                appender.list.clear();
            }
            if (!found.isEmpty()) break;
        }
        return found;
    }

    private static void release(ByteBuf buffer) { buffer.release(); }
}
