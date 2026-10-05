# Native network transport (epoll vs NIO) — 2026-10-05

Revision: the `transport` option on branch `claude/keen-dijkstra-uenl1m`, which already includes relay flush batching. `NetworkTransport` selects epoll on Linux, kqueue on macOS and NIO elsewhere. The listener, player sessions, backend dials and DNS all use the selected transport. Both variants below use the same build. Only `ProxySessionBenchmark --transport` differs.

Decision rule, set before running: keep epoll as the `AUTO` default on Linux only if no workload shows a consistent regression against NIO. Claim an improvement only where the ranges barely overlap.

## Environment and method

Same as the [batched-flush record](2026-10-05-relay-batched-flush.md): Linux 6.18 container, 4 vCPU Intel Xeon, Temurin 25.0.4.1, Netty 4.2.2.Final, loopback. Each JVM ran with `--enable-native-access=ALL-UNNAMED`. For each workload, three epoll/NIO JVM pairs ran in alternation with `--repeats 2`, giving six proxy phases per cell. All echoes were verified and there were no errors.

```sh
java --enable-native-access=ALL-UNNAMED -cp "<classes>:<install>/lib/*" dev.moonbridge.core.session.ProxySessionBenchmark \
  <burst32 | w1 | w16 arguments from the batched-flush record> --repeats 2 --transport epoll|nio
```

## Results

Values are the median and [min–max] of proxy-side absolute metrics. The direct path uses plain blocking Java sockets in both variants and drifted between runs, so the proxy/direct ratio is not used here.

| Workload | Transport | Proxy frames/s | p50 ms | p95 ms | Proxy I/O CPU ns/frame |
| --- | --- | ---: | ---: | ---: | ---: |
| burst32 | epoll | 804,984 [488,131–983,608] | 0.391 [0.352–0.549] | 1.022 [0.701–2.657] | 1348 [1000–2777] |
| burst32 | nio | 698,966 [416,849–842,129] | 0.484 [0.380–0.741] | 1.399 [0.993–3.117] | 1394 [1022–3200] |
| w1 | epoll | 46,122 [36,380–54,094] | 0.060 [0.037–0.064] | 0.120 [0.073–0.139] | 28110 [21710–30610] |
| w1 | nio | 38,464 [32,695–44,040] | 0.070 [0.067–0.075] | 0.143 [0.129–0.164] | 31969 [24005–39624] |
| w16 | epoll | 116,523 [90,765–144,130] | 0.365 [0.262–0.522] | 0.905 [0.655–1.515] | 7606 [6387–10162] |
| w16 | nio | 100,896 [79,929–112,237] | 0.409 [0.358–0.426] | 1.492 [0.840–2.128] | 10490 [7259–14410] |

Raw output: [2026-10-05-network-transport-raw.txt](2026-10-05-network-transport-raw.txt).

## Interpretation

No workload showed a regression, and epoll had the better median on every metric. The only separation without range overlap is window-1 p50 latency (0.060 vs 0.070 ms). The other differences point in the same direction but their ranges overlap, so this run supports "no worse, likely somewhat better", not a specific gain. The decision rule is met and `AUTO` keeps epoll on Linux.

## Limits

This was a single loopback host. kqueue (macOS) and the Windows NIO fallback were not measured; on those platforms only the selection and fallback logic is covered by unit tests. The run did not use cross-host links, real Forge traffic or production load.
