# Session / PluginHost split: relay throughput check (2026-10-06)

Question: does splitting `Session` and friends into collaborating classes (PR "Split Session, PluginHost ...", merged as `0ad66b6`) change proxy throughput?

Decision rule, set before running: the branch is acceptable if its median proxy roundtrips/s and per-frame proxy CPU are within 3% of the pre-split commit `1efbbaf` and the ranges overlap.

Method: `ProxySessionBenchmark`, `--connections 4 --messages 6000 --warmup 1000 --repeats 3 --window 4 --burst 8 --transport auto` (kqueue on this host), leak detection disabled as in the distribution. One JVM per run, variant order alternated (base/branch, branch/base, ...). 6 JVM runs x 3 repeats = 18 samples per variant and mode, modes `initial` and `post-transfer`. A later 4 JVM run, 12 sample recheck was taken on the final merged code.

| mode | variant | samples | roundtrips/s median | range | proxy CPU ns/frame median |
|---|---|---|---|---|---|
| initial | base | 18 | 26475 | 26066-27145 | 8554 |
| initial | branch | 18 | 26680 | 25912-27084 | 8574 |
| post-transfer | base | 18 | 26306 | 25491-27540 | 8834 |
| post-transfer | branch | 18 | 26431 | 25641-26953 | 8790 |
| initial (final code) | base | 12 | 26391 | 25434-27268 | 8693 |
| initial (final code) | branch | 12 | 26197 | 25274-27179 | 8663 |

Result: differences are +0.8%, +0.5% and -0.7%, all inside the run-to-run range; no errors in any sample. The rule is met.

Raw output: `2026-10-06-session-split-base-raw.txt`, `2026-10-06-session-split-branch-raw.txt` (the first 36 samples per variant; the recheck is summarised above only).

Not covered: a single macOS host over loopback with a synthetic backend, no Forge, no real client, no capacity claim; the login path, plugin command dispatch and the backend control channel were not benchmarked (they are off the PLAY relay path and were only exercised by the test suite).
