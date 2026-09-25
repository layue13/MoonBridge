# KeepAliveBridge relay comparison — 2026-09-25

Source: `bab964dd0198419d9aa8a546bb058bf9d21fd2a5` on `rewrite/greenfield`.

Environment: Windows NT 10.0.26200.0; AMD Ryzen 7 7800X3D (8 cores, 16 logical processors); Zulu OpenJDK 25.0.4.1. All phases used the same loopback echo backend, JVM, 8 connections, 2,000 measured messages and 200 warmup messages per connection, and 4,096-byte synthetic frame payloads. Connect and setup time was excluded. The relay phases alternated order across three rounds, bracketed by direct baselines. The backend byte count was 793,372,800 in each run, and every echo matched the sent frame.

| Window | Path | Round 2 throughput (roundtrips/s) | Round 3 throughput (roundtrips/s) | Round 2 p95 (ms) | Round 3 p95 (ms) |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | Framed relay | 89,561 | 93,897 | 0.107 | 0.099 |
| 1 | Framed relay with `KeepAliveBridge` | 93,128 | 93,707 | 0.101 | 0.098 |
| 16 | Framed relay | 179,961 | 174,398 | 0.692 | 0.659 |
| 16 | Framed relay with `KeepAliveBridge` | 148,642 | 183,515 | 0.762 | 0.648 |

Exact command output: [window 1](2026-09-25-4096b-bridge-window1.txt) and [window 16](2026-09-25-4096b-bridge-window16.txt). The direct baseline increased from 129,654 to 189,511 roundtrips/s at window 1 and from 200,038 to 306,553 at window 16, showing substantial warmup or system drift. These measurements do not establish a stable throughput or latency gain or regression from the Keep Alive handler. The earlier relay measurements used a different relay event-loop arrangement and are not a direct before/after comparison.

This is a synthetic ordinary-frame echo test of the actual `KeepAliveBridge` pipeline. It does not exercise login, online encryption, Forge handshake, transfer packet rewrites, mod traffic, or a real client/server pack. It is not the requested real-pack performance acceptance test.
