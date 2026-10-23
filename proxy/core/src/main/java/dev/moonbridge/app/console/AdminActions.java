package dev.moonbridge.app.console;

import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.TransferResult;

import java.util.concurrent.CompletionStage;

/** What the built-in console commands can change on the running proxy. */
public interface AdminActions {
    CompletionStage<DisconnectResult> kick(PlayerIdentity player, String reason);

    CompletionStage<TransferResult> send(PlayerIdentity player, String server);

    /** The configured level of a logger, or the level it inherits, in upper case. */
    String logLevel(String logger);

    /** Sets a logger's level at runtime; nothing is written to logback.xml. */
    void setLogLevel(String logger, String level);

    /** Stops the proxy the same way as Ctrl+C. */
    void stop();
}
