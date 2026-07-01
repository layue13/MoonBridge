# JavaDoc Reference

This page describes how the source-level JavaDoc is organized and how to generate the API reference locally.

## Generate

```powershell
.\gradlew.bat javadoc
```

Generated HTML is written under each module's `build/docs/javadoc` directory, for example:

- `proxy-api/build/docs/javadoc`
- `proxy-plugin-api/build/docs/javadoc`
- `proxy-network/build/docs/javadoc`
- `proxy-admin-api/build/docs/javadoc`

Use `--rerun-tasks` when checking documentation coverage after large comment changes:

```powershell
.\gradlew.bat --rerun-tasks javadoc
```

## Documentation Scope

The project JavaDoc is focused on public contracts:

- `proxy-api`: backend server descriptors, health, load, drain policy, registry contracts, and routing-facing models.
- `proxy-plugin-api`: plugin lifecycle, command, event, scheduler, player, and server service contracts.
- `proxy-routing`: route requests, route decisions, and weighted health-aware routing diagnostics.
- `proxy-protocol`: packet metadata, protocol states, directions, flags, and packet classification.
- `proxy-codec-minecraft`: Minecraft VarInt, compression frame, encryption, zstd, and custom payload helpers.
- `proxy-network`: Netty server lifecycle, admission control, authentication runtime, forwarding runtime, status runtime, and transfer results.
- `proxy-admin-api`: registry persistence, admin registry mutation, transfer service, HTTP views, metrics, and diagnostics DTOs.
- `proxy-observability`: metric events, in-memory sinks, counters, samples, and snapshot DTOs.
- `proxy-bootstrap`: YAML loading, runtime configuration records, and validation results.
- `proxy-compression`, `proxy-packet-analysis`, `proxy-native`, `proxy-command`, and `proxy-plugin`: strategy, policy, runtime, command, event, and plugin-loader contracts.

Package-private relay handlers and low-level implementation helpers are documented only when their behavior is not obvious from the surrounding public contract.

## Maintenance Rules

- Public interfaces, public records, public enums, public classes, and public constructors should have JavaDoc.
- Record JavaDoc should explain component semantics, units, and default behavior.
- Methods returning owned Netty `ByteBuf` instances must state ownership and release responsibility.
- Stable diagnostic strings, rejection reasons, thresholds, and timeout units should be documented where they cross module boundaries.
- Chinese documentation is maintained in [zh-CN/javadoc.md](zh-CN/javadoc.md) with the same structure as this page.
