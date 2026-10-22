# Post-transfer synthetic session benchmark

Run date: 2026-09-26 (Asia/Shanghai). Revision: `883d439`. Windows 11, Zulu OpenJDK 25.0.4.1, local loopback. The same benchmark JVM alternated direct and proxy phases against the same synthetic echo backend. Each phase used four connections, 4,096-byte PLAY bodies, 1,000 validated warmup exchanges and 10,000 measured exchanges per connection. Proxy clients transferred to the replacement backend before warmup; direct clients connected to that same backend. Login, transfer and warmup were outside the timed phase.

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 1 -Mode post-transfer
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 16 -Mode post-transfer
```

| In-flight window | Direct roundtrips/s range | Proxy roundtrips/s range | Paired proxy/direct ratios, rounds 1–5 | Proxy p95 latency range |
| ---: | ---: | ---: | --- | ---: |
| 1 | 29,298–50,301 | 25,262–29,452 | 0.560, 0.624, 0.899, 0.553, 0.602 | 0.172–0.206 ms |
| 16 | 77,019–92,764 | 63,256–98,302 | 0.734, 0.848, 0.950, 0.709, 1.125 | 0.776–1.757 ms |

All ten direct and ten proxy phases validated 40,000 measured echoes per phase with zero errors. Raw outputs: [window 1](2026-09-26-883d439-post-transfer-window1.txt), [window 16](2026-09-26-883d439-post-transfer-window16.txt).

Direct throughput itself varied substantially, so this run cannot attribute differences from prior revisions to code changes. The payload is an ordinary synthetic packet; it does not exercise target Forge modpack traffic, an actual client, mod-specific packets, or production network conditions. The target pack remains necessary for performance acceptance.
