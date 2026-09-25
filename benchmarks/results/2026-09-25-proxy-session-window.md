# Proxy session in-flight window benchmark

Run date: 2026-09-25 (Asia/Shanghai); repository revision before these benchmark edits: `6b98439bcba815c0177d200ed76426e7c4606b4e`.
Environment: Windows 11 Enterprise, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1, PowerShell 7.6.5.

This benchmark compares direct loopback TCP to the fake backend with the same client connected through the real `ProxySessionListener` to that backend. Each run uses one JVM and alternates direct/proxy phase order. Each client uses the same window algorithm on both paths: send up to Window framed PLAY requests, read and validate their echoes in TCP order, then send one replacement per echo. Warmup uses the same algorithm and is excluded from timing. Each reported latency starts immediately before writing its request and ends after reading its echo; with a window above one, it includes time spent in the in-flight batch.

Each command ran `:proxy-core:installDist`, benchmark compilation, and the benchmark. All Gradle builds reported `BUILD SUCCESSFUL`; benchmark invocations exited 0. `installDist` was up to date. No full Gradle check was run.

## Small smoke, sequence-validated Window=1

Command:

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 1 -Messages 10 -Warmup 2 -Payload 64 -Repeats 1 -Window 1
```

Output:

```text
direct-1: roundtrips=10 echoes=10 correct=10 errors=0 roundtrips/s=12074.4 warmup_roundtrips=2 window=1 one_way_MiB/s=0.74 latency_ms_p50=0.065 p95=0.153 p99=0.153 elapsed_ms=0.828
proxy-1: roundtrips=10 echoes=10 correct=10 errors=0 roundtrips/s=3370.1 warmup_roundtrips=2 window=1 one_way_MiB/s=0.21 latency_ms_p50=0.256 p95=0.384 p99=0.384 elapsed_ms=2.967
```

## Representative sequence-validated paired runs

Both commands used 4 connections, 2,000 measured roundtrips and 200 warmup roundtrips per connection, 4,096-byte PLAY bodies, and 3 alternating direct/proxy repeats. Each phase therefore checked 8,000 measured and 800 warmup echoes. Window was the only parameter changed.

Commands:

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 2000 -Warmup 200 -Payload 4096 -Repeats 3 -Window 16
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 2000 -Warmup 200 -Payload 4096 -Repeats 3 -Window 1
```

| Window | Phase | Roundtrips | Correct echoes | Roundtrips/s | One-way MiB/s | p50 ms | p95 ms | p99 ms | Elapsed ms |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 16 | Direct 1 | 8,000 | 8,000 | 97,653.9 | 381.46 | 0.580 | 0.778 | 1.125 | 81.922 |
| 16 | Proxy 1 | 8,000 | 8,000 | 60,290.1 | 235.51 | 0.877 | 1.849 | 2.727 | 132.692 |
| 16 | Proxy 2 | 8,000 | 8,000 | 72,540.5 | 283.36 | 0.739 | 1.397 | 2.413 | 110.283 |
| 16 | Direct 2 | 8,000 | 8,000 | 103,791.5 | 405.44 | 0.610 | 0.706 | 0.739 | 77.078 |
| 16 | Direct 3 | 8,000 | 8,000 | 103,876.7 | 405.77 | 0.524 | 0.709 | 0.751 | 77.014 |
| 16 | Proxy 3 | 8,000 | 8,000 | 102,314.4 | 399.67 | 0.617 | 0.768 | 0.885 | 78.190 |
| 1 | Direct 1 | 8,000 | 8,000 | 65,689.0 | 256.60 | 0.054 | 0.079 | 0.096 | 121.786 |
| 1 | Proxy 1 | 8,000 | 8,000 | 27,124.2 | 105.95 | 0.140 | 0.182 | 0.218 | 294.939 |
| 1 | Proxy 2 | 8,000 | 8,000 | 34,926.4 | 136.43 | 0.108 | 0.150 | 0.181 | 229.053 |
| 1 | Direct 2 | 8,000 | 8,000 | 75,223.4 | 293.84 | 0.048 | 0.064 | 0.077 | 106.350 |
| 1 | Direct 3 | 8,000 | 8,000 | 73,981.8 | 288.99 | 0.050 | 0.068 | 0.079 | 108.135 |
| 1 | Proxy 3 | 8,000 | 8,000 | 38,905.9 | 151.98 | 0.096 | 0.130 | 0.178 | 205.624 |

All representative phases passed with 8,000 correctly sequenced measured echoes and 800 correctly sequenced warmups each. The W=16 command checked 48,000 measured echoes; W=1 checked another 48,000. Each command completed `:proxy-core:installDist`, compilation, and benchmarking successfully in its own JVM. The separate commands ran W=16 first, so machine scheduling and JVM startup can affect cross-command comparisons. These synthetic loopback results do not establish Forge pack behavior, real backend throughput, cross-host performance, or production capacity.

## Correctness check

The preliminary identical-payload runs were superseded because they could not detect duplicate or reordered echoes. The current benchmark writes a fixed-width, big-endian sequence number into body bytes 1–4, leaving the first byte as PLAY packet ID `0x03` and keeping the configured payload length fixed. The client checks frame-body length, packet ID, the exact next sequence number, and every remaining deterministic payload byte for every warmup and measured echo. Warmup uses sequences starting at zero; measured traffic continues at the warmup count. Each client owns its mutable frame buffer, so concurrent clients cannot overwrite each other's sequence fields. Payloads must be at least five bytes to hold the packet ID and sequence.

The current small run checked 10 measured and 2 warmup echoes per phase. The representative W=1/W=16 runs checked 96,000 measured echoes and 9,600 warmup echoes across both settings, all with correct sequence, packet ID, and payload.
