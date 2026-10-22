# Post-transfer forwarding after the Forge handshake gate

Run date: 2026-09-25 (Asia/Shanghai). Core revision: `562e00be8e176784e458f0b99b5815a15a1b1212`. Windows 11, Zulu OpenJDK 25.0.4.1, local loopback. This measurement followed the real Prism Forge transfer run; no Uranium or Prism process was left running during the benchmark.

The existing `run-proxy-session.ps1` harness compared direct clients against the actual proxy session after a transfer to the same synthetic echo backend. Both paths used four connections, 4,096-byte PLAY bodies, 1,000 validated warmup exchanges and 10,000 measured exchanges per connection, with five alternating direct/proxy rounds. Login, transfer and warmup were excluded from timing. Both runs completed every phase with 40,000 correct echoes and zero errors.

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 1 -Mode post-transfer
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5 -Window 16 -Mode post-transfer
```

| Window | Direct roundtrips/s range | Proxy roundtrips/s range | Paired proxy/direct ratio, rounds 1–5 | Proxy p95 latency range |
| ---: | ---: | ---: | --- | ---: |
| 1 | 58,027–74,754 | 33,765–41,091 | 0.550, 0.690, 0.576, 0.588, 0.591 | 0.119–0.135 ms |
| 16 | 95,177–126,914 | 88,698–104,177 | 0.723, 0.715, 0.821, 1.052, 1.085 | 0.698–1.152 ms |

Raw outputs: [window 1](2026-09-25-562e00b-post-transfer-window1.txt), [window 16](2026-09-25-562e00b-post-transfer-window16.txt).

These ranges overlap the [pre-gate post-transfer measurement](2026-09-25-post-transfer-session.md). The paired ratios and p95 ranges are similar, so this synthetic packet mix does not show a stable, large regression from the Forge gate. The two measurements were separate JVM runs and direct throughput drifted within both; they cannot prove zero overhead. The timed payload is an ordinary packet with no entity-ID rewrite, not a Forge modpack workload. Target-pack latency and capacity remain unverified.
