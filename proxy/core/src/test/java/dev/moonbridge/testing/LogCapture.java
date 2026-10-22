package dev.moonbridge.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Collects the formatted lines one logger writes while it is open. */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(String loggerName) {
        logger = (Logger) LoggerFactory.getLogger(loggerName);
        appender.start();
        logger.addAppender(appender);
    }

    /** Lines as "LEVEL message". */
    public List<String> lines() {
        // AppenderBase appends while holding the appender's monitor.
        synchronized (appender) {
            return appender.list.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
        }
    }

    public List<String> lines(Level level) {
        return lines().stream().filter(line -> line.startsWith(level + " ")).toList();
    }

    @Override public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
