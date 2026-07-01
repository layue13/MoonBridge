package example.strataproxy.hello;

import dev.strataproxy.plugin.PluginContext;
import dev.strataproxy.plugin.ProxyPlugin;
import dev.strataproxy.plugin.command.CommandResult;
import dev.strataproxy.plugin.command.CommandSpec;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class HelloPlugin implements ProxyPlugin {
    private PluginContext context;

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        context.commands().register(new CommandSpec(
                "hello",
                List.of(),
                "",
                "Send a hello message.",
                command -> CompletableFuture.completedFuture(CommandResult.ok("Hello, " + command.source().name() + "."))));
    }

    @Override
    public void onEnable() {
        context.logger().info("Hello plugin enabled");
    }
}
