# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

MoonBridge is a session proxy for Minecraft 1.7.10 Forge (protocol 5): login/auth, frame-level forwarding, transfers between backends, plus a plugin API, a backend control channel and channel messaging. User-facing docs are Chinese and live in `docs/` (start at `docs/README.md`); [docs/architecture.md](docs/architecture.md) is the authoritative boundary description.

## Build and test

JDK 25 is required (Gradle toolchain; the daemon JVM is pinned to 25). `gradlew` is not executable in a fresh Linux checkout, so use `bash ./gradlew` (`.\gradlew.bat` on Windows). The LuckPerms plugin builds from a submodule: run `git submodule update --init --recursive` first.

```sh
bash ./gradlew check                                  # everything CI runs, ~7 min: tests, installed-dist smoke tests, permissionAcceptance
bash ./gradlew :proxy-core:test                       # proxy-core tests only
bash ./gradlew :proxy-core:test --tests '*RawRelayTest'                 # one class
bash ./gradlew :proxy-core:test --tests '*RawRelayTest.methodName'      # one method
bash ./gradlew :proxy-core:installDist                # runnable distribution in proxy-core/build/install/moonbridge
```

The slow tests are the session integration tests (`ProxySessionListenerTest`, `SessionTransferTest`, `InitialRoutingTest`, `SourceReleaseTransferTest`); iterate with `--tests` and run `check` once before pushing.

`.gitea/workflows/ci.yml` runs `check` and publishes Maven modules only on pushes to `main`. A GitHub PR has no CI, so a local `check` is the only verification before merge.

## Modules

- `proxy-core` — the proxy runtime (`dev.moonbridge.app` = startup/config, `dev.moonbridge.core.*` = everything else). The only module that uses Netty.
- `proxy-plugin-api` — the stable plugin boundary. Plugins are discovered with `META-INF/services/dev.moonbridge.api.Plugin` and loaded in separate class loaders by `PluginHost`.
- `messaging-api`, `messaging-protocol`, `backend-channel-client`, `backend-bukkit-api`, `backend-bukkit` — backend side. These compile with `--release 8` (Java 8 backends such as Uranium) and must not pull in newer APIs.
- `luckperms-moonbridge` — native LuckPerms platform plugin, built from `vendor/luckperms` via the included build `luckperms-moonbridge/upstream-build`. `proxy-core:check` depends on it (`permissionAcceptance`).
- `proxy-build-logic` — Kotlin convention plugins shared by all modules.

## Architecture that spans files

**Session threading.** `ProxySessionListener` owns the listener, event loops and the UUID→session index. Each `Session` (one per player connection) confines its mutable state to the frontend event loop, and its backend channels are created on that same loop (`SessionChannels.backendBootstrap` enforces it). Login verification, backend DNS and plugin callbacks run off that loop and re-enter it by posting a task; results are checked against the current connection identity so a stale async result cannot act on a reconnected player. Plugin work goes through bounded pools that reject when full.

**Session layout.** `core.session` holds `Session` (the Netty handler and lifecycle owner) plus the pieces it delegates to: `FrontendLogin` (handshake, status, encryption, Mojang verification), `InitialRouter` (placement decision and first backend dial), `BackendHandshakes`. `core.session.transfer` is `TransferCoordinator`, which reaches the session only through the `TransferHost` port (Session supplies it as an adapter) and owns all transfer state. `core.session.play` has the per-frame PLAY handlers and relay start-up buffering; `core.session.channel` has backend dialing and pipeline lookups. Dependencies point one way: `session` -> `transfer` -> `play`/`channel`.

**Data path.** After login, `RawRelay` forwards already-framed PLAY frames between the two channels without copying. Per-frame concerns are separate inbound handlers (`KeepAliveBridge`, `PlayerCommandInterceptor`, `TabCompletionBridge`, `TransferFrameHandler`) in front of the relay. Backpressure works by pausing reads on the source when the peer is not writable; the relay flushes once per read batch (`channelReadComplete`).

**Transfers.** `Session.beginTransfer` dials and logs into the candidate backend while the old link keeps serving, buffers frames from both sides (`TransferFrameBuffer`, `TransitionBuffer`), swaps relays, and rewrites the player's entity IDs afterwards (`Minecraft1710EntityIds`). Forge clients also need an FML reset/handshake before the world switch; `TransferCandidate` and `PlayObservation` track that state. Failure before the client-visible switch restores the old link; after it, the session is closed.

**Transport.** `NetworkTransport` picks epoll / kqueue / NIO and supplies the channel types for the listener, backend dials and the DNS resolver together. Create event loops only through it: a bare `MultiThreadIoEventLoopGroup` silently uses a `LinkedBlockingQueue` instead of the MPSC queue, which showed up as a measurable per-frame cost.

**Buffer ownership.** Production runs with Netty leak detection disabled (the start script passes `-Dio.netty.leakDetection.level=disabled`), so refcounting must be right by construction. Do not write new `try/finally release()` blocks. Use `ByteBufs.fill/use` for allocate-write-release-on-failure, extend `FrameTransformHandler` for per-frame inbound handlers (its `transform` borrows the frame and returns what to forward), and use Netty's `MessageToMessageEncoder/Decoder` for codecs. Tests run every `proxy-core` test class under PARANOID leak detection: `LeakGate` (auto-registered via `junit-platform.properties`) fails a class that leaks a buffer. It cannot see `Unpooled.wrappedBuffer(byte[])` buffers, so assert `refCnt()` directly for those.

**Backends and plugins.** Backends come from static config, plugin registration (`Servers.register`, owner-scoped handles with generations) or the optional control channel (`BackendControlService`: separate authenticated blocking sockets on virtual threads, leases that outlive short disconnects). Initial routing is fail-closed: with no placement plugin it uses only `initialRouting.servers`. Permissions are a provider interface (`PermissionService`) with per-connection subjects that are prepared before login admission and released on any exit.

## Testing notes

- `EmbeddedChannel.close()` destroys the pipeline synchronously, so "message arrives after close" races only reproduce on a real NIO loop (see `RawRelaySocketTest`).
- A suite-wide check that every hand-written `release()` is guarded was done by replacing each with a no-op and running the covering tests; keep that bar for new refcount code (the rationale and results are in `docs/testing.md`).

## Performance work

Benchmarks live in `benchmarks/` (`ProxySessionBenchmark`, PowerShell runners; on Linux compile it against `proxy-core/build/install/moonbridge/lib` and run `java --enable-native-access=ALL-UNNAMED -cp ... dev.moonbridge.core.session.ProxySessionBenchmark --burst 32 --window 4 --transport epoll`). The existing records show the house method: set the decision rule first, rotate variant order across JVM runs, report median and range, keep raw output next to a dated `benchmarks/results/*.md` that states what it does not cover, and prefer profile samples (JFR) over overlapping throughput ranges. Single-host loopback numbers are never capacity claims.
