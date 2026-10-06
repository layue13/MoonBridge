package dev.moonbridge.core.plugin;

import dev.moonbridge.api.Plugin;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Thread factories and executors shared by the plugin host's services. */
final class PluginThreads {
    private static final ThreadFactory PLAYER_COMPLETION_THREADS = Thread.ofVirtual()
            .name("moonbridge-plugin-player-", 0).factory();
    /** Plugin callbacks on player futures run here, never on the player's Netty event loop. */
    static final Executor PLAYER_COMPLETIONS = task -> PLAYER_COMPLETION_THREADS.newThread(task).start();

    private PluginThreads() { }

    static ThreadFactory named(String prefix) {
        AtomicLong number = new AtomicLong();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
