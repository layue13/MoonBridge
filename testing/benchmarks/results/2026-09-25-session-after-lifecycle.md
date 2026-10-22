# Session benchmark after lifecycle fixes

Run date: 2026-09-25 (Asia/Shanghai). Tested revision: `0b15bd85e04571928f039a20d78b10d431d7d1f6`.

Environment: Microsoft Windows 11 Enterprise, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1, PowerShell 7.6.5. Each command completed `:proxy-core:installDist`, compiled the benchmark, and exited successfully. No source code changed between the two runs.

The same JVM, fake TCP backend, client code, 4,096-byte PLAY body, four clients, and in-flight window were used for each direct/proxy pair. Setup, login, and warmup were excluded from timing. Direct/proxy phase order alternated by repeat. Every echo was checked for packet ID, sequence, length, and deterministic payload.

| In-flight window | Measured roundtrips per phase | Repeats | Direct roundtrips/s range | Proxy roundtrips/s range | Proxy p95 latency range | Result |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 40,000 | 5 | 57,900.4–76,418.9 | 34,439.1–41,751.7 | 0.118–0.130 ms | All 200,000 proxy echoes correct; zero errors |
| 16 | 40,000 | 5 | 97,589.0–128,250.8 | 87,206.3–104,044.3 | 0.694–1.259 ms | All 200,000 proxy echoes correct; zero errors |

Direct throughput fell during both commands without a source change. The ranges overlap the earlier measurement at revision `e617fd4`, but separate runs cannot establish a code-level speedup or rule out a small regression. Within these runs, the proxy stayed slower than direct for window 1. For window 16, paired throughput changed with phase order and machine scheduling, so a single proxy/direct ratio would be misleading.

Commands and raw outputs:

- [Window 1](2026-09-25-proxy-session-0b15bd8-window1.txt): `./benchmarks/run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 1`
- [Window 16](2026-09-25-proxy-session-0b15bd8-window16.txt): `./benchmarks/run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 16`

This is a loopback echo test of the proxy's offline login and PLAY forwarding path. It does not include a real Forge client, modpack, backend, cross-host network, or gameplay. Those conditions require separate validation when the target pack is available.
