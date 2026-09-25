# Same-event-loop relay callback screening — 2026-09-25

Source baseline: `6b98439` on `rewrite/greenfield`. The experimental variant ran the relay's write-completion work inline when its callback was already on the source event loop, instead of always queuing another event-loop task. The variant passed `RawRelayTest` but was reverted after measurement; production code remains at the baseline.

Environment: Windows, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1. Both runs used `benchmarks/run-proxy-session.ps1 -Connections 8 -Messages 10000 -Warmup 1000 -Payload 1024 -Repeats 2`. Each timed phase completed 80,000 synthetic offline PLAY echoes over loopback; login and warmup were excluded. At the time, all request bodies were identical, so echo comparison did not detect duplication or reordering. The two versions were run sequentially, each in a fresh JVM, with alternating direct/proxy phase order within the run.

| Version | Direct 1 | Proxy 1 | Proxy 2 | Direct 2 | Proxy p95 1 / 2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Inline callback variant | 124,712/s | 73,155/s | 76,663/s | 126,523/s | 0.132 / 0.121 ms |
| Existing queued callback | 121,688/s | 73,425/s | 74,662/s | 124,572/s | 0.132 / 0.122 ms |

This comparison did not show a consistent enough improvement to justify changing callback scheduling. It does not cover a real 1.7.10 Forge pack or cross-host network conditions. The next performance comparison should exercise more than one packet in flight through the complete session path.
