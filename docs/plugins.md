# Plugins and In-Game Commands

StrataProxy loads proxy plugins from the `plugins/` directory next to the active config file. Plugin jars are optional; the proxy starts normally when the directory is missing.

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

Plugins can register commands, subscribe to events, query players and servers, schedule async work, and request player transfers through the plugin context. Plugin code should not block Netty event-loop threads.
