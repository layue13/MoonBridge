# Proxy session offline PLAY echo benchmark

Run date: 2026-09-25 (Asia/Shanghai)
Repository revision: `38b02266e6db7b1583116f53358cf97ab42f27b1`
Runtime: Zulu OpenJDK 25.0.4.1, Windows PowerShell 7.6.5, AMD Ryzen 7 7800X3D

This measures only an offline synthetic Minecraft 1.7.10 PLAY packet echo. The direct case connects the benchmark client to the fake backend. The proxy case connects the same client to `ProxySessionListener`, which connects to that same backend. The backend sends Login Success, a valid Join Game and Position and Look, then echoes framed PLAY payloads. The client reads login and both initial PLAY packets and completes warmup before timing. The synthetic body starts with packet id `0x03` and fills the rest deterministically.

The two paths run in the same JVM with the same fake backend, client code, payload and per-phase connection concurrency. Phases alternate order across repeats. Login and warmup are excluded from timings. Throughput uses application payload bytes sent one way (excluding frame length bytes). Latency is measured from writing one request frame through reading its echo.

## Small smoke

Command:

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 1 -Messages 10 -Warmup 2 -Payload 64 -Repeats 1
```

| Phase | Measured roundtrips | Echoes correct | Roundtrips/s | One-way MiB/s | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| Direct | 10 | 10/10 | 10,377.8 | 0.63 | 0.071 | 0.122 | 0.122 |
| Proxy | 10 | 10/10 | 2,926.8 | 0.18 | 0.286 | 0.595 | 0.595 |

Two warmup echoes ran per phase and were also checked, then excluded from measured counts and timings.

## Larger smoke

Command:

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 1000 -Warmup 100 -Payload 1024 -Repeats 2
```

Each phase measured 4,000 roundtrips; each phase also completed and checked 400 warmup echoes.

| Phase order | Measured roundtrips | Echoes correct | Roundtrips/s | One-way MiB/s | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| Direct 1 | 4,000 | 4,000/4,000 | 65,230.9 | 63.70 | 0.058 | 0.076 | 0.091 |
| Proxy 1 | 4,000 | 4,000/4,000 | 28,986.3 | 28.31 | 0.133 | 0.172 | 0.207 |
| Proxy 2 | 4,000 | 4,000/4,000 | 31,192.5 | 30.46 | 0.123 | 0.161 | 0.191 |
| Direct 2 | 4,000 | 4,000/4,000 | 62,558.6 | 61.09 | 0.062 | 0.079 | 0.093 |

Both commands completed `:proxy-core:installDist`, compilation and the benchmark successfully. Every measured response matched its request; each reported measured phase had zero echo errors.

These short loopback runs check benchmark correctness and provide an initial local comparison. They do not establish real client/server performance, Forge compatibility, pack behavior, network behavior outside loopback, or a stable capacity limit. The small sample has only ten measured roundtrips per phase; even the larger sample is a local microbenchmark and is sensitive to JVM and machine scheduling.
