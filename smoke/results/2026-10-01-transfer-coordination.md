# Transfer coordination validation — 2026-10-01

Baseline: `main@876de1a056a79b807a03f3877cbe6414b60e86dc`. Contract: [transfer coordination](../../docs/transfer-coordination.md). This record covers the optional generic proxy contract, not PlayerDataSQL persistence or mod inventory synchronization.

## Automated checks

On Windows with Zulu OpenJDK 25.0.4.1, `./gradlew.bat --no-daemon check :proxy-core:installDist` succeeded in 6 minutes 26 seconds. The generated JUnit reports contained 49 suites and 309 tests, with zero failures, errors or skips. The build also passed installed-distribution help/version/configuration checks, backend API/host artifact checks, and the native LuckPerms permission acceptance run.

The focused transfer suite had 29 passing cases across `SourceReleaseTransferTest`, `SessionTransferTest`, and `TransferFrameBufferTest`. Coverage includes source EOF before confirmation, confirmation before target LoginStart, a published player with no current server during the gap, discarded gap frames, preparation failure preserving the source relay, incomplete-frame rollback and replay, candidate death before/after source release, frontend cancellation, late callbacks, target registration replacement, buffer ownership/release, and existing vanilla/Forge transitions.

Two concrete defects were found and repaired before this result:

- Buffers must retain frames while source detach can still be rolled back. They switch to discard mode only after detach becomes irreversible, so failed preparation/cutover cannot silently lose retained source operations.
- A ready candidate must retain same-read-batch packets until its handler is actually removed. `handOff()` now executes immediately before pipeline removal on the same event loop. The regression test sends JoinGame and Position/Look together and checks Position/Look reaches the client.

## Real Uranium round trip

The installed proxy distribution loaded an external SPI smoke plugin and dynamically registered its destination. The plugin subscribed through the public `TransferPreparingEvent` API and requested source release on both transfers. Its confirmation callback completed asynchronously after a 250 ms delay. Two isolated offline Uranium servers ran on Java 8.0.492, with Forge 10.13.4.1614 and no additional gameplay mods or PlayerDataSQL plugins.

Server artifact: `Uranium-1710-rfg-bridge-d7a68f934f-dirty-server.jar`, SHA-256 `A05ECB7CA50CB00390A690608CC243738711C2EC079C37FBA3B0119BB261373A`. A read-only instrumentation agent observed selected final-defined classes to investigate the later backend data-load design; it returned null from its transformer and did not replace class bytes. This run is not a performance measurement.

Command:

```powershell
./smoke/local-uranium-transfer.ps1 -BundlePath build/uranium-event-bundle -InstalledPlugin -ReleaseSource -ReturnToOld -DebugSession -BackendJavaAgent <observational-agent.jar>
```

The protocol-5 client completed Forge A→B→A. Each direction received one Forge reset, one replacement ServerHello and two world-transition Respawns; after returning to A the client sustained two KeepAlives. Both transfer futures returned `NETWORK_READY`. Proxy callback logs demonstrate off-I/O execution:

| Forward transfer step | Local timestamp | Thread |
| --- | --- | --- |
| Preparation | 02:25:02.639 | moonbridge-plugin-transfer-1 |
| Source socket released | 02:25:02.644 | moonbridge-plugin-transfer-1 |
| Asynchronous confirmation | 02:25:02.897 | ForkJoinPool.commonPool-worker-1 |
| Destination login frames flushed | 02:25:02.899 | moonbridge-session-io-2 |
| Transfer ready | 02:25:03.124 | uranium-transfer-smoke |

The return confirmation was at 02:25:05.378; destination login frames followed at 02:25:05.379 and transfer completion at 02:25:05.484. The script verified both backend login/disconnect logs and stopped its own proxy and servers. The existing user demo was not modified.

Local raw evidence: `build/local-uranium-transfer-66c44a7f4ab4411a88ea09f4cc1fa231/`, including `proxy.stdout.log`, backend logs and final-defined class dumps. These disposable runtime files are excluded from Git.

## Limits

The synthetic protocol client is not a real Prism visual/gameplay test. The smoke callback confirms only its deliberate asynchronous barrier; it does not observe a database commit. Additional mod inventories, a production Uranium build, arbitrary plugin behavior and PlayerDataSQL crash recovery are outside this result. Performance evidence and its uncertainty are recorded separately in [the benchmark report](../../benchmarks/results/transfer-coordination-validation.md).
