package dev.moonbridge.app.console;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.Players;
import dev.moonbridge.api.TransferResult;
import net.kyori.adventure.text.Component;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Console actions on the running proxy: the same player operations plugins use, plus Logback and shutdown. */
public final class LiveAdminActions implements AdminActions {
    private final Players players;

    public LiveAdminActions(Players players) { this.players = Objects.requireNonNull(players, "players"); }

    @Override public CompletionStage<DisconnectResult> kick(PlayerIdentity player, String reason) {
        return players.disconnect(player, Component.text(reason));
    }

    @Override public CompletionStage<TransferResult> send(PlayerIdentity player, String server) {
        return players.transfer(player, server);
    }

    @Override public String logLevel(String logger) {
        return context().getLogger(logger).getEffectiveLevel().toString();
    }

    @Override public void setLogLevel(String logger, String level) {
        context().getLogger(logger).setLevel(Level.toLevel(level));
    }

    @Override public void stop() {
        // System.exit runs the proxy's shutdown hook, which stops it exactly as Ctrl+C does. It is called from a fresh
        // thread because the hook closes the console, which waits for nothing but must not be the exiting thread.
        Thread.ofPlatform().name("moonbridge-stop").start(() -> System.exit(0));
    }

    private static LoggerContext context() { return (LoggerContext) LoggerFactory.getILoggerFactory(); }
}
