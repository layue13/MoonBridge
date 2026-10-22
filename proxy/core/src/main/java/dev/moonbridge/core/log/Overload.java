package dev.moonbridge.core.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Throttled warnings for plugin work the proxy refused because a bounded pool or queue was full. */
public final class Overload {
    private static final Logger LOGGER = LoggerFactory.getLogger("moonbridge.plugins");
    private static final Map<String, ThrottledWarning> WARNINGS = new ConcurrentHashMap<>();

    private Overload() { }

    /** {@code area} is a fixed description such as "plugin commands"; never pass peer or plugin text. */
    public static void rejected(String area) {
        WARNINGS.computeIfAbsent(area, name -> new ThrottledWarning(LOGGER,
                "Overloaded: rejected {} " + name + " request(s); a plugin may be blocking its worker threads",
                1, TimeUnit.MINUTES)).record();
    }
}
