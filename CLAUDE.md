# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

MoonBridge is a session proxy for Minecraft 1.7.10 Forge (protocol 5): login/auth, frame-level forwarding, transfers between backends, plus a plugin API, a backend control channel and channel messaging. User-facing docs are Chinese and live in `docs/` (start at `docs/README.md`); [docs/architecture.md](docs/architecture.md) is the authoritative boundary description.

## Build and test

JDK 25 is required (Gradle toolchain; the daemon JVM is pinned to 25). Use `./gradlew` (`.\gradlew.bat` on Windows).

The LuckPerms platform plugin compiles LuckPerms from the `proxy/vendor/luckperms` submodule, which is slow and download-heavy, so it is opt-in: `-Pluckperms` includes `luckperms-moonbridge`, its tests, `permissionAcceptance` and the plugin in the distribution (run `git submodule update --init --recursive` first). CI always passes it; run it locally when touching permissions, plugin loading or the distribution layout.

```sh
./gradlew check                                       # tests, installed-dist smoke tests, benchmark compile
./gradlew -Pluckperms check                           # what CI runs, ~7 min: adds the LuckPerms plugin and permissionAcceptance
./gradlew release                                     # build/release: server zip, backend jar, client mod (-PskipClientMod to omit it)
./gradlew :proxy-core:test                            # proxy-core tests only
./gradlew :proxy-core:test --tests '*RawRelayTest'     # one class
./gradlew :proxy-core:test --tests '*RawRelayTest.m'   # one method
./gradlew :proxy-core:installDist                     # proxy/core/build/install/moonbridge: java -jar moonbridge.jar
```

The slow tests are the session integration tests (`ProxySessionListenerTest`, `SessionTransferTest`, `InitialRoutingTest`, `SourceReleaseTransferTest`); iterate with `--tests` and run `check` once before pushing.

`.github/workflows/verify.yml` runs `-Pluckperms check` and builds `client-mod/` on every pull request; `.github/workflows/ci.yml` runs `-Pluckperms check release`, uploads `build/release/` and publishes the Maven modules on pushes to `main`. Run `-Pluckperms check` locally before pushing anyway when the change can affect it: a red PR costs a round trip.

## Modules

Directories group the modules by side; Gradle project names stay flat, so task paths and Maven artifact IDs are the same as the names below. `settings.gradle.kts` maps each name to its directory.

```
proxy/core          proxy-core            proxy/plugin-api   proxy-plugin-api
proxy/luckperms     luckperms-moonbridge  proxy/vendor/luckperms (submodule)
messaging           messaging             backend            backend
client-mod/         separate build        build-logic/       convention plugins (included build)
testing/smoke/      smoke scripts and dated records; testing/benchmarks/ likewise. Never packaged.
```

- `proxy-core` — the proxy runtime (`dev.moonbridge.app` = startup/config, `dev.moonbridge.core.*` = everything else). The only module that uses Netty.
- `proxy-plugin-api` — the stable plugin boundary. Plugins are discovered with `META-INF/services/dev.moonbridge.api.Plugin` and loaded in separate class loaders by `PluginHost`.
- `messaging` — message envelope, channel API and wire codec shared by the proxy and backends.
- `backend` — the Bukkit/Uranium host plugin `MoonBridgeBackend` (its jar bundles `messaging`), its service API (`dev.moonbridge.bukkit`) and the control channel client (`dev.moonbridge.backendchannel`). Business plugins compile against this jar `compileOnly`.
- `messaging` and `backend` use `moonbridge.java8-library-conventions` (`--release 8`, for Java 8 backends such as Uranium) and must not pull in newer APIs.
- `luckperms-moonbridge` — native LuckPerms platform plugin, built from `proxy/vendor/luckperms` via the included build `proxy/luckperms/upstream-build`; only included with `-Pluckperms`.
- `client-mod/` — the 1.7.10 Forge WebSocket client mod. A separate Gradle build (GTNH template, RetroFuturaGradle, Mixins) with its own wrapper: `cd client-mod && ./gradlew build`. Its `net` package is free of Minecraft classes and uses Minecraft's bundled Netty 4.0.10.
- `build-logic` — Kotlin convention plugins: `java-library`, `java8-library`, `application`, and `publishing-conventions` (shared package repository; set `moonbridgePublication { artifactId ... }` for a standard publication).

## Architecture that spans files

**Session threading.** `ProxySessionListener` owns the listener, event loops and the UUID→session index. Each `Session` (one per player connection) confines its mutable state to the frontend event loop, and its backend channels are created on that same loop (`SessionChannels.backendBootstrap` enforces it). Login verification, backend DNS and plugin callbacks run off that loop and re-enter it by posting a task; results are checked against the current connection identity so a stale async result cannot act on a reconnected player. Plugin work goes through bounded pools that reject when full.

**Session layout.** `core.session` holds `Session` (the Netty handler and lifecycle owner) plus the pieces it delegates to: `FrontendLogin` (handshake, status, encryption, Mojang verification), `InitialRouter` (placement decision and first backend dial), `BackendHandshakes`. `core.session.transfer` is `TransferCoordinator`, which reaches the session only through the `TransferHost` port (Session supplies it as an adapter) and owns all transfer state. `core.session.play` has the PLAY data path (`RawRelay`, the per-frame handlers, relay start-up buffering); `core.session.channel` has `NetworkTransport`, backend dialing, pipeline lookups and `clientAddress` (the player IP: a trusted forwarded address or the socket peer; use it instead of `remoteAddress()`). `core.websocket` is the optional WebSocket frontend; after the upgrade the pipeline behind `WebSocketFrameAdapter` is the same as for TCP. Dependencies point one way: `session` -> `transfer` -> `play`/`channel`.

**Data path.** After login, `RawRelay` forwards already-framed PLAY frames between the two channels without copying. Per-frame concerns are separate inbound handlers (`KeepAliveBridge`, `PlayerCommandInterceptor`, `TabCompletionBridge`, `TransferFrameHandler`) in front of the relay. Backpressure works by pausing reads on the source when the peer is not writable; the relay flushes once per read batch (`channelReadComplete`).

**Transfers.** `Session.beginTransfer` dials and logs into the candidate backend while the old link keeps serving, buffers frames from both sides (`TransferFrameBuffer`, `TransitionBuffer`), swaps relays, and rewrites the player's entity IDs afterwards (`Minecraft1710EntityIds`). Forge clients also need an FML reset/handshake before the world switch; `TransferCandidate` and `PlayObservation` track that state. Failure before the client-visible switch restores the old link; after it, the session is closed.

**Transport.** `NetworkTransport` picks epoll / kqueue / NIO and supplies the channel types for the listener, backend dials and the DNS resolver together. Create event loops only through it: a bare `MultiThreadIoEventLoopGroup` silently uses a `LinkedBlockingQueue` instead of the MPSC queue, which showed up as a measurable per-frame cost.

**Buffer ownership.** Production runs with Netty leak detection disabled (`ProxyMain` sets `io.netty.leakDetection.level=disabled` before Netty loads unless the property is given), so refcounting must be right by construction. Do not write new `try/finally release()` blocks. Use `ByteBufs.fill/use` for allocate-write-release-on-failure, extend `FrameTransformHandler` for per-frame inbound handlers (its `transform` borrows the frame and returns what to forward), and use Netty's `MessageToMessageEncoder/Decoder` for codecs. Tests run every `proxy-core` test class under PARANOID leak detection: `LeakGate` (auto-registered via `junit-platform.properties`) fails a class that leaks a buffer. It cannot see `Unpooled.wrappedBuffer(byte[])` buffers, so assert `refCnt()` directly for those.

**Backends and plugins.** Backends come from static config, plugin registration (`Servers.register`, owner-scoped handles with generations) or the optional control channel (`BackendControlService`: separate authenticated blocking sockets on virtual threads, leases that outlive short disconnects). Initial routing is fail-closed: with no placement plugin it uses only `initialRouting.servers`. Permissions are a provider interface (`PermissionService`) with per-connection subjects that are prepared before login admission and released on any exit.

## Testing notes

- `EmbeddedChannel.close()` destroys the pipeline synchronously, so "message arrives after close" races only reproduce on a real NIO loop (see `RawRelaySocketTest`).
- A suite-wide check that every hand-written `release()` is guarded was done by replacing each with a no-op and running the covering tests; keep that bar for new refcount code (the rationale and results are in `docs/testing.md`).

## Performance work

Benchmarks live in `testing/benchmarks/` (`ProxySessionBenchmark`, PowerShell runners; on Linux compile it against `proxy/core/build/install/moonbridge/moonbridge.jar` and run `java --enable-native-access=ALL-UNNAMED -cp ... dev.moonbridge.core.session.ProxySessionBenchmark --burst 32 --window 4 --transport epoll`). The existing records show the house method: set the decision rule first, rotate variant order across JVM runs, report median and range, keep raw output next to a dated `testing/benchmarks/results/*.md` that states what it does not cover, and prefer profile samples (JFR) over overlapping throughput ranges. Single-host loopback numbers are never capacity claims.
