# Local synthetic relay measurements — 2026-09-25

Source: `537815766ead7c776f176b7d72ba4d6a83dcdfc0` on `rewrite/greenfield`.

Environment: Windows NT 10.0.26200.0, AMD Ryzen 7 7800X3D (8 cores, 16 logical processors), Zulu OpenJDK 25.0.4.1. All paths use the same loopback echo backend, JVM, connection count, message count, and frame bytes for each payload. Each phase warms its own connections. The relay order alternates; direct baselines bracket the relay phases. Setup time is excluded. All three runs completed with the expected byte count and no payload mismatch.

| Frame payload | Connections | Measured messages / connection | Raw relay, rounds 2–3 (roundtrips/s) | Framed relay, rounds 2–3 (roundtrips/s) | Raw p95, rounds 2–3 (ms) | Framed p95, rounds 2–3 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 256 B | 8 | 2,000 | 83,549 / 83,541 | 80,638 / 78,227 | 0.119 / 0.120 | 0.124 / 0.132 |
| 4 KiB | 8 | 2,000 | 68,918 / 67,311 | 65,701 / 67,820 | 0.136 / 0.144 | 0.147 / 0.138 |
| 64 KiB | 4 | 500 | 21,007 / 21,890 | 19,916 / 24,397 | 0.227 / 0.214 | 0.258 / 0.201 |

Exact command output: [256 B](2026-09-25-256b.txt), [4 KiB](2026-09-25-4096b.txt), [64 KiB](2026-09-25-65536b.txt).

The direct baseline rose substantially between the first and last phase in every run, so JVM and system warmup still affect these numbers. The framed path preserved packet boundaries and, in these local runs, showed no consistent large throughput or p95 regression versus the raw path. These results do not measure login, Forge mod traffic, compression, online authentication, cross-server transfer, or a real client/server pack; they are a screening measurement, not an acceptance result for the goal.
