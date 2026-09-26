package dev.strataproxy.api;

/** Runs on a bounded plugin worker, never on a player's network event loop. */
@FunctionalInterface
public interface CommandHandler {
    void execute(CommandInvocation invocation) throws Exception;
}
