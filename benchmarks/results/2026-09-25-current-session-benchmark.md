# Current proxy session benchmark

Run date: 2026-09-25 (Asia/Shanghai). Tested revision: `e617fd478458706f7c7d12029fe4df9a1f61966b`.

Environment: Windows 11 Enterprise, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1, PowerShell 7.6.5. Each command ran `:proxy-core:installDist`, compiled the benchmark, and exited successfully. No source code was changed between runs.

The same JVM, fake TCP backend, client code, 4,096-byte PLAY body, four clients, and in-flight window were used for each direct/proxy pair. Phases alternated order by repeat. Setup, login, and warmup were excluded from timing. The client checked packet ID, sequence, body length, and deterministic payload on every echo.

| Window | Measured roundtrips per phase | Warmup per phase | Repeats | Direct roundtrips/s range | Proxy roundtrips/s range | Result |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 40,000 | 4,000 | 5 | 57,300.8–64,596.5 | 33,286.2–40,496.1 | All echoes correct; zero errors |
| 16 | 40,000 | 4,000 | 5 | 96,568.9–130,166.6 | 86,499.8–101,342.1 | All echoes correct; zero errors |
| 16 | 160,000 | 16,000 | 5 | 96,414.2–124,807.1 | 100,530.3–102,760.3 | All echoes correct; zero errors |

The direct baseline dropped substantially during the window-16 runs while proxy throughput was steadier. In the longest run, direct fell from 124,807.1 to roughly 96,500 roundtrips/s after the first repeat. A paired proxy/direct ratio then changed from 0.82 to about 1.04–1.06 without a code change. These measurements establish that the full login/session/relay path worked under this synthetic traffic, but they do not establish a stable throughput advantage or overhead estimate. Window 1 stayed slower through the proxy in all five repeats. The measured p95 roundtrip latency was 0.073–0.079 ms direct and 0.125–0.140 ms through the proxy.

Commands and raw outputs:

- [Window 1, 10,000 messages per client](2026-09-25-proxy-session-e617fd4-long-window1.txt): `./benchmarks/run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 1`
- [Window 16, 10,000 messages per client](2026-09-25-proxy-session-e617fd4-long-window16.txt): `./benchmarks/run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 16`
- [Window 16, 40,000 messages per client](2026-09-25-proxy-session-e617fd4-longer-window16.txt): `./benchmarks/run-proxy-session.ps1 -Connections 4 -Messages 40000 -Warmup 4000 -Payload 4096 -Repeats 5 -Window 16`

This is a loopback echo test of the proxy itself. It is not backend load observation and does not include a real 1.7.10 Forge client, modpack, backend, cross-host network, or gameplay. Those conditions still need separate validation when the pack is available.
