package dev.strataproxy.infrastructure.plugin.command;

import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSource;
import dev.strataproxy.plugin.command.CommandSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultCommandRegistryTest {
    @Test
    void executesRegisteredCommandAndAlias() {
        var registry = new DefaultCommandRegistry();
        registry.register(new CommandSpec("server", List.of("send"), "", "", context ->
                CompletableFuture.completedFuture(CommandResult.ok(context.arguments().get(0)))));

        var result = registry.execute(source(), "/send lobby-1").toCompletableFuture().join();

        assertTrue(result.handled());
        assertTrue(result.success());
        assertEquals("lobby-1", result.message());
    }

    @Test
    void ignoresUnknownCommand() {
        var result = new DefaultCommandRegistry().execute(source(), "/unknown").toCompletableFuture().join();

        assertFalse(result.handled());
    }

    private static CommandSource source() {
        return new CommandSource() {
            @Override
            public String name() {
                return "Steve";
            }
        };
    }
}
