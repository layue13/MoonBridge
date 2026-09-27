# Channel messaging: contract and rationale

## Problem and acceptance criteria

A plugin on backend A must be able to notify or query a proxy plugin or backend B using a named channel. Plugins must not create network connections, distribute credentials, implement proxy relay plugins, or depend on a connected player. Multiple plugins in one backend share one authenticated connection. Both ends receive an immutable message with a framework-assigned identity and an authenticated source.

Acceptance requires real socket tests with no players: backend/proxy and backend/backend requests; notification fan-out; concurrent responses matched to their requests; independent plugin disable; bounded overload; timeout including queueing; disconnect without replay; and old-connection isolation. A Java 8 Bukkit host artifact provides the shared service. Unit tests and socket tests do not establish deployment acceptance in a running Forge pack.

## Deriving the model

1. A connection authenticates a backend process, not each plugin in that process. The proxy therefore derives the source from the authenticated connection. A plugin label is useful for local lifecycle ownership, but is not a separately authenticated remote principal.
2. A channel identifies a business communication domain and its permission boundary. A destination identifies the receiving node. Conflating these would force applications to encode backend names in channels or build their own routing protocol.
3. A notification has no business response and may have several subscribers. A request needs exactly one responsible handler at its destination. Separate `subscribe` and `onRequest` registrations remove the multiple-reply ambiguity; registering a second request handler for the same node/channel fails.
4. Network failure can occur after execution but before a reply. Neither a timeout nor a successful socket write proves whether the business operation executed. Messaging is live-only, with bounded queues and no automatic cross-connection replay. Durable state and idempotency belong to the application.
5. A message identity is useful across proxy hops and in logs. It is independent of a transport operation's temporary receipt correlation. Every message, including a notification and a reply, gets a UUID. A reply points to the request UUID. Routing preserves identities; applications do not generate correlation fields for ordinary calls.
6. The backend game thread must not perform socket I/O or wait for remote replies. The host supplies the executor for callbacks. Timeouts start at submission and cover queueing, dispatch, and waiting; expired queued messages must not be transmitted later.

## Public model

`Endpoint` is either the proxy or a named backend. `Message` contains `id`, `kind` (`EVENT`, `REQUEST`, `REPLY`), `channel`, authenticated `source`, `target`, `replyTo`, and a defensively copied binary `payload`. A null event target means publication to subscribers; requests and replies always have one target. Only replies have `replyTo`. Business message types and schema versions can be encoded in the payload; they are not transport kinds. Connection generations remain internal.

Each plugin gets an owned `Messaging` scope and obtains a `MessageChannel` by name. `subscribe` receives events, `onRequest` produces an asynchronous reply, `send` sends an event to one endpoint, `request` obtains one full reply message, and `publish` fans out an event. Closing a plugin scope revokes only that plugin's registrations and pending calls. Retained handles cannot create new work after close.

`send` returns a receipt containing the message ID and a result. `ACCEPTED` means that the destination node accepted the notification into its bounded handler dispatch; it does not mean that business code finished. `NO_SUBSCRIBER`, `NOT_CONNECTED`, `BACKPRESSURED`, `REJECTED`, `TIMED_OUT`, and `FAILED` are distinct. A timeout or unknown failure cannot prove that the recipient did not execute the operation. A request fails with a typed error for no handler, overload, timeout, disconnect, rejection, or handler failure.

`publish` visits the proxy and each currently connected backend authorized to receive the channel, including the publisher. Each node dispatches to its current local subscribers. The result contains one result per candidate node, including `NO_SUBSCRIBER`; it is not an atomic transaction across nodes. This first implementation deliberately avoids a replicated subscription directory and its registration/reconnect races. A publication accepts at most 256 candidate nodes including the proxy; exceeding that limit rejects the whole publication before dispatch. Direct sends and requests do not have this fan-out limit. A future optimization may introduce acknowledged subscription routing without changing the public semantics.

## Ownership and transport

The proxy provides the messaging service through `PluginContext`. A single Java 8 Bukkit bridge owns the backend client and publishes a service through Bukkit's `ServicesManager`. Consumer plugins declare a dependency on the bridge and use its API with a provided/compile-only dependency. They must not shade separate copies of the service API or client. The bridge supplies game-thread callback execution and automatically closes scopes on `PluginDisableEvent`.

The existing authenticated registration, advertised game address validation, heartbeat lease, and backend directory ownership remain the transport foundation. Backend-to-backend traffic travels through the proxy's built-in router. Routing neither changes game connections nor requires a proxy business plugin.

Protocol version 2 adds a shared bounded message codec. Per-hop operation IDs correlate delivery receipts and transport responses; end-to-end message UUIDs and reply references survive forwarding. The proxy overwrites or validates the source against the authenticated connection and binds pending calls to the exact connection. Receiving permissions are checked independently of sending permissions. Version 1 peers must be upgraded together; incompatible versions fail explicitly rather than decoding each other's frames.

## Verification boundaries

The implementation must retain the rich-text/command-completion tests while integrating current main. Required messaging tests cover two independent backend clients and the proxy, event subscribers versus a unique request handler, correct source and reply identities, caller payload mutation, malformed/bounded protocol frames, stalled handlers, slow socket readers, plugin disable, and disconnect/reconnect. A packaged bridge must contain the Java 8 API/client implementation once and exclude Bukkit classes.

Actual Bukkit/Uranium loading, main-thread behavior under real server scheduling, and production capacity are separate acceptance evidence and must not be inferred from compilation or isolated socket tests.
