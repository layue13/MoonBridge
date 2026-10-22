# Local pipelined relay screening — 2026-09-25

Source: `e34e6f3e0e5d912e5d51ccd2d486114508355d84` on `rewrite/greenfield`.

Environment: Windows NT 10.0.26200.0, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1. Each run uses one JVM, one loopback echo backend, 8 connections, 2,000 measured frames per connection, 200 warmup frames per connection, 4 KiB frame payloads, and three alternating relay rounds. The direct baseline brackets each run. The window is the maximum number of messages each client can have in flight. The benchmark checks every echoed payload and the aggregate backend byte count.

| Window | Path | Round 2 throughput | Round 3 throughput | Round 2 p95 | Round 3 p95 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | Raw relay | 68,612/s | 68,766/s | 0.139 ms | 0.138 ms |
| 1 | Framed relay | 65,524/s | 66,515/s | 0.148 ms | 0.144 ms |
| 16 | Raw relay | 136,703/s | 163,605/s | 0.293 ms | 0.289 ms |
| 16 | Framed relay | 89,419/s | 123,889/s | 0.950 ms | 0.585 ms |

Exact output: [window 1](2026-09-25-4096b-window1.txt), [window 16](2026-09-25-4096b-window16.txt).

In this local run, the framed relay was close to the raw relay with one outstanding frame. With 16 outstanding frames, the raw relay had higher throughput and lower p95 in both later rounds. The direct baseline increased strongly between the start and end of both runs, so warmup or system drift still affects the measurements. The two windows use different client sending mechanics; compare paths within one window, not absolute results across windows. This is a synthetic screening result, not a proven optimization target or 1.7.10 Forge acceptance result. It excludes login, compression, Forge negotiation, mods, and real client/backend behavior.
