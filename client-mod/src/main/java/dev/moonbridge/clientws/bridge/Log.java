package dev.moonbridge.clientws.bridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Shared logger; kept out of the mixin classes so they add no static state to vanilla classes. */
public final class Log {

    public static final Logger LOG = LogManager.getLogger("MoonBridgeWs");

    private Log() {}
}
