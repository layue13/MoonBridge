# Plugins and In-Game Commands

StrataProxy loads proxy plugins from the `plugins/` directory next to the active config file. Plugin jars are optional; the proxy starts normally when the directory is missing.

The public plugin surface is the `dev.strataproxy:proxy-plugin-api` artifact. Plugin projects should depend on that API only; `proxy-network`, `proxy-app`, and other runtime modules are internal implementation details.

For local development from this repository:

```powershell
.\gradlew.bat :proxy-plugin-api:publishToMavenLocal
```

Then a plugin project can use:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("dev.strataproxy:proxy-plugin-api:0.1.0-SNAPSHOT")
}
```

## Built-in Player Commands

These commands are intercepted by the proxy and are not forwarded to the backend server:

```text
/server <server>
/hub
/lobby
/servers
/glist
```

`/server`, `/hub`, and `/lobby` use the same Bungee-like backend re-login transfer path as `strataproxy-admin players transfer` and supported BungeeCord `Connect` plugin messages.

## Plugin Entry Point

A plugin jar can expose a `dev.strataproxy.plugin.ProxyPlugin` implementation with `strataproxy-plugin.properties` in the jar root:

```properties
id=example
name=Example Plugin
version=1.0.0
main=com.example.ExamplePlugin
```

It can also use a standard Java `ServiceLoader` provider for `dev.strataproxy.plugin.ProxyPlugin`.

Plugins can register commands, subscribe to events, query players and servers, register dynamic backends, schedule async work, and request player transfers through the plugin context. Plugin code should not block Netty event-loop threads.

## Dynamic Backend Registration

Plugins can call `context.servers().register(...)` to publish runtime backends for service discovery, room servers, instance servers, or external orchestrator sync. Registrations default to `ServerPersistence.EPHEMERAL`, which affects only the running proxy. Use `ServerPersistence.PERSISTENT` when the backend should survive a proxy restart.

Plugin-registered servers are stamped with owner metadata. By default, a plugin may only replace, remove, or drain servers it registered itself; it cannot silently overwrite YAML static servers or servers owned by another plugin.

```java
var registration = new ServerRegistration(
        "arena-1",
        new InetSocketAddress("127.0.0.1", 25570),
        Set.of("arena"),
        Set.of("modern-forwarding"),
        ServerProtocolRange.any(),
        100,
        80,
        100,
        false,
        Map.of("group", "arena"),
        ServerPersistence.EPHEMERAL);

context.servers().register(registration).thenAccept(result -> {
    if (!result.success()) {
        context.logger().warn("Failed to register backend: {} {}", result.outcome(), result.message());
    }
});
```

Capability names follow the same semantics as config values. Matching is case-insensitive and normalizes `-` to `_`, so `modern-forwarding` maps to `MODERN_FORWARDING`.

## Minimal Plugin

```java
public final class HelloPlugin implements ProxyPlugin {
    @Override
    public void onLoad(PluginContext context) {
        context.commands().register(new CommandSpec(
                "hello",
                List.of(),
                "",
                "Send a hello message.",
                command -> CompletableFuture.completedFuture(
                        CommandResult.ok("Hello, " + command.source().name() + "."))));
    }
}
```

The repository includes a standalone example under `examples/hello-plugin`. Build it after publishing the API locally:

```powershell
.\gradlew.bat :proxy-plugin-api:publishToMavenLocal
.\gradlew.bat -p examples/hello-plugin build
```

Copy the resulting plugin jar into the `plugins/` directory next to the active StrataProxy config file, then restart the proxy.
