# Relay flush batching candidate

Run date: 2026-09-25 (Asia/Shanghai). Base revision: `042efcf6e3e99fc0c9dbc3fcc3402a64329c9b35`. Environment: Windows 11, Zulu OpenJDK 25.0.4.1, local loopback. The candidate changed the ordinary relay from `writeAndFlush` per frame to `write` per frame and one `flush` on each inbound `channelReadComplete`. Transfer buffer replay also emitted a read-complete event. The candidate was not committed.

A unit test confirmed that two frames delivered in one inbound read batch caused one outbound flush and forwarded the original `ByteBuf` instances. `gradlew check` and `smoke/installed-discovery.ps1` passed after a synthetic transfer test was updated to emit its missing read-complete event. This establishes behavior under those tests, not a throughput improvement.

The same full-session echo benchmark used for the [current baseline](2026-09-25-current-session-benchmark.md) ran with four clients, a 4,096-byte PLAY body, 10,000 measured and 1,000 warmup roundtrips per client, and five repeats. Each run alternated direct and proxy phases. Every echo was correct.

| In-flight window | Direct roundtrips/s range | Candidate proxy roundtrips/s range | Earlier proxy range |
| ---: | ---: | ---: | ---: |
| 1 | 45,530.0–58,518.0 | 27,911.4–32,456.9 | 33,286.2–40,496.1 |
| 16 | 94,958.7–121,315.7 | 84,088.5–104,631.1 | 86,499.8–101,342.1 |

The direct baseline shifted between runs. These data do not isolate a performance effect from batching. Window 1 showed no apparent benefit, while window 16 was similar to the earlier run. The candidate also depended on a read-complete event for every forwarded batch, including replay. Given no demonstrated performance benefit and the extra relay/transfer coupling, the implementation was reverted. The raw outputs are [window 1](2026-09-25-proxy-session-batched-flush-window1.txt) and [window 16](2026-09-25-proxy-session-batched-flush-window16.txt).

This is a proxy performance experiment with a fake backend. It is not backend load observation and does not establish real Forge modpack behavior.
