# Transfer coordination validation

## Acceptance and scope

The no-subscriber transfer path should not add per-PLAY-packet hooks or buffers. For ordinary post-transfer PLAY traffic, treat a regression within 3% as the target. Report paired baseline/candidate measurements and their run-to-run spread; if variance overlaps or exceeds that budget, classify the result as inconclusive rather than claiming it is within budget. The structural hot-path review is separate evidence from this measurement.

The new controlled transfer benchmark measures whole transfer time through `NETWORK_READY` with loopback fake Minecraft 1.7.10 backends. It exercises `PluginHost` and its asynchronous transfer-event dispatcher, with no subscriber, one/four immediate allow listeners, one/four immediate release-source listeners, and one/four release-source listeners with 4 ms asynchronous preparation and release callbacks. Each scenario runs at concurrency 1, 16, and 64, with three discarded warmup batches and at least 100 measured transfers. The default five measured batches produce at least 100 samples at every concurrency; a user-specified lower repeat count still keeps at least 100 samples. It reports completed transfers/s and p50/p95/p99 transfer latency. It is a coordination-path microbenchmark, not a real-backend or gameplay benchmark.

Run it after the full check and Uranium runtime smoke have released the CPU:

```powershell
.\benchmarks\run-transfer-path.ps1 -Repeats 5
```

The `--only-no-subscriber` harness filter keeps its main class compatible with baseline runtime jars: the new participant plugin is isolated in a reflectively loaded helper, so this filter never loads the new event API. After compiling the harness against the candidate libraries, compare baseline and candidate no-subscriber transfer paths with the same harness and settings. For example, from `MoonBridge-design` with the detached baseline sibling already built:

```powershell
$baselineLib = Join-Path (Resolve-Path ..\MoonBridge-baseline) "proxy-core\build\install\moonbridge\lib"
.\benchmarks\run-transfer-path.ps1 -Repeats 5 -OnlyNoSubscriber -RuntimeLibDirectory $baselineLib
.\benchmarks\run-transfer-path.ps1 -Repeats 5 -OnlyNoSubscriber
```

For a five-pair alternating baseline/candidate run that saves complete raw output under `%TEMP%`:

```powershell
.\benchmarks\run-transfer-paired.ps1 -BaselineLibDirectory $baselineLib
```

This isolates coordination/handoff latency but still uses loopback fake backends; it does not substitute for the ordinary PLAY throughput comparison below.

For sampled allocation profiling when JFR is available:

```powershell
.\benchmarks\run-transfer-path.ps1 -Repeats 5 -RecordJfr
```

The JFR file is written under the system temporary directory. JFR profile allocation events are sampled; they are not an exact allocation counter.

## Baseline screen

An independent detached clone at `work/MoonBridge-baseline` is pinned to baseline `876de1a056a79b807a03f3877cbe6414b60e86dc`. Its local raw outputs are named `2026-10-01-baseline-{initial,post-transfer}-window{1,16}.txt` and are intentionally not part of this change. Environment: Windows, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1, four loopback clients, 4,096-byte PLAY payload, 1,000 warmup and 10,000 measured round trips per connection, five repetitions. The initial and post-transfer modes use the existing synthetic PLAY echo benchmark; this first screen is not a candidate comparison.

| Mode | Window | Proxy throughput, median (range) | Proxy p99, median (range) |
|---|---:|---:|---:|
| Initial | 1 | 71,459.5 (61,886–73,582) rt/s | 0.077 (0.070–0.112) ms |
| Post-transfer | 1 | 71,503.0 (60,607–72,143) rt/s | 0.081 (0.070–0.115) ms |
| Initial | 16 | 167,938.4 (133,088–190,294) rt/s | 0.628 (0.538–1.271) ms |
| Post-transfer | 16 | 182,537.3 (127,469–185,567) rt/s | 0.542 (0.530–1.505) ms |

The spread is much wider than the 3% target. These baseline-only runs are superseded by the paired comparison below; they were only an initial screen.

## Controlled transfer matrix

One full JFR-profiled candidate run covered 21 cases. Each case used three discarded warmup batches, then 100 samples at concurrency 1, 112 at 16, and 320 at 64. All samples returned `NETWORK_READY`; no scenario errors occurred. The run used one persistent frontend session per concurrent player, alternating the target between A and B for successive transfers. This avoids measuring connection setup for every sample.

Measurement environment: Windows loopback, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1, dated 2026-10-01. Baseline runtime libraries were built from detached `876de1a056a79b807a03f3877cbe6414b60e86dc`; candidate libraries were built from the source changes committed immediately after the JFR run as `e8cc2a72bdaa59873a0b1825d1cad2f2957c6789`. The paired runs below used that candidate commit.

| Transfer preparation scenario | Concurrency 1 (p50/p99 ms; transfers/s) | Concurrency 16 (p50/p99 ms; transfers/s) | Concurrency 64 (p50/p99 ms; transfers/s) |
|---|---:|---:|---:|
| No subscribers | 0.910 / 1.641; 882.9 | 0.874 / 1.171; 11,784.5 | 2.934 / 3.757; 12,376.4 |
| 1 immediate allow listener | 0.866 / 1.401; 983.0 | 0.932 / 1.264; 11,003.0 | 3.283 / 3.854; 10,660.0 |
| 4 immediate allow listeners | 0.816 / 1.121; 1,061.3 | 0.957 / 1.348; 11,142.2 | 1.870 / 4.057; 14,140.7 |
| 1 immediate release-source listener | 0.835 / 1.118; 1,084.9 | 1.138 / 1.728; 9,249.1 | 2.125 / 3.348; 13,322.6 |
| 1 release-source listener, 4 ms prepare + callback | 10.683 / 12.900; 93.1 | 10.955 / 12.719; 1,390.9 | 10.714 / 12.785; 5,038.6 |
| 4 immediate release-source listeners | 0.695 / 0.828; 1,312.0 | 1.023 / 1.343; 10,671.6 | 2.908 / 3.631; 12,937.3 |
| 4 release-source listeners, 4 ms prepare + callback each | 41.865 / 45.969; 24.0 | 41.382 / 44.167; 382.8 | 40.846 / 46.208; 1,457.1 |

The 4 ms case is a scheduling demonstration, not a performance comparison: the host processes listeners sequentially, so four delayed preparation stages and four delayed release callbacks account for roughly 32 ms before other transfer work. The table is one JFR-instrumented run and should not be used to rank listener counts. Its recording, `%TEMP%\moonbridge-transfer-20261001-023806.jfr`, lasted 80 seconds and contains 677 `jdk.ObjectAllocationSample` events. Allocation events are sampled and span every scenario; there is no per-scenario allocation count, exact allocation rate, retained-memory measurement, or baseline JFR comparison.

Two earlier high-churn attempts stopped in the final 64-concurrent case with `BindException: Address already in use` while opening a new frontend socket. Windows had 14,000+ loopback TIME_WAIT entries against a 16,384-port dynamic range. The harness was changed to retain each frontend session across batches and alternate its target, reducing frontend socket churn. The final matrix completed. The failed attempts are harness/environment failures, not transfer result samples.

## Paired no-subscriber transfer-path comparison

The same compiled harness ran against baseline libraries from detached `876de1a056a79b807a03f3877cbe6414b60e86dc` and candidate libraries in five alternating pairs. Each side completed 532 transfers per process (100/112/320 per concurrency); total: 5,320 successful samples, no worker failures. The candidate event participant helper is loaded only for subscriber cases, so the baseline zero-subscriber run does not resolve the new event API.

| Concurrency | Baseline median transfers/s | Candidate median transfers/s | Paired median rate change (range) | Baseline / candidate p99 median (ms) | Paired p99 delta median (range, ms) |
|---:|---:|---:|---:|---:|---:|
| 1 | 905.6 | 902.6 | -0.08% (-4.97% to +1.92%) | 1.586 / 1.659 | -0.026 (-0.298 to +0.295) |
| 16 | 5,741.9 | 5,915.7 | +2.80% (-1.60% to +8.50%) | 2.762 / 2.612 | -0.051 (-0.453 to +0.166) |
| 64 | 7,181.2 | 7,508.9 | +3.29% (-6.92% to +5.54%) | 5.426 / 6.817 | -0.885 (-1.778 to +1.886) |

The paired transfer measurements drift over the run and are wider than 3% at 16 and 64 clients. They do not establish a transfer-throughput improvement or regression.

## Paired ordinary PLAY comparison

The existing echo benchmark ran with four clients, 100,000 measured messages, 10,000 warmup messages, 4,096-byte bodies, five internal repetitions per JVM, and windows 1 and 16 in both initial and post-transfer modes. Baseline and candidate order alternated over five pairs per case, with a fresh JVM for each side. The direct-backend phase was retained as a control. Across 40 JVM runs, all 200 proxy and 200 direct measurement rows reported 400,000/400,000 correct echoes and zero errors.

Rates are the median proxy rate across the five per-JVM pair medians. Paired rate change is candidate/baseline for each pair, then the median and full five-pair range. p99 values are medians of those same per-JVM medians; delta is paired candidate minus baseline. The direct column gives the median paired direct-backend rate change and its range.

| Mode / window | Baseline / candidate proxy rate (rt/s) | Paired proxy rate change (range) | Baseline / candidate proxy p99 (ms) | Paired p99 delta median (range, ms) | Direct control rate change median (range) |
|---|---:|---:|---:|---:|---:|
| Initial / 1 | 54,849.3 / 54,810.9 | -0.26% (-0.81% to +35.80%) | 0.097 / 0.096 | -0.002 (-0.039 to +0.004) | -0.19% (-0.38% to +36.75%) |
| Post-transfer / 1 | 54,708.7 / 54,830.3 | +0.22% (-3.43% to +1.32%) | 0.104 / 0.096 | -0.007 (-0.012 to +0.032) | -0.99% (-18.28% to +0.69%) |
| Initial / 16 | 183,454.7 / 183,581.3 | +0.06% (-0.77% to +0.66%) | 0.618 / 0.618 | -0.001 (-0.002 to +0.000) | +0.09% (-0.50% to +0.44%) |
| Post-transfer / 16 | 182,798.9 / 182,557.2 | +0.18% (-0.51% to +0.84%) | 0.623 / 0.623 | +0.002 (-0.004 to +0.038) | +0.14% (-0.41% to +0.80%) |

The 1-window control varies far beyond the 3% budget: the initial-mode +35.80% proxy outlier coincides with a +36.75% direct-control outlier, and post-transfer direct control ranges from -18.28% to +0.69%. The 16-window paired rates and direct controls stayed within 1% in this run. The overall ordinary-PLAY result is therefore **inconclusive against the 3% target**, not a pass or regression finding. The benchmark is an offline synthetic echo workload, not a real Minecraft server workload.

## Structural default-path review

Against baseline `876de1a`, `core/relay/RawRelay.java`, `core/session/TransferFrameHandler.java`, and `core/session/PlayObservation.java` have no source changes. Transfer preparation is selected once at transfer initiation; absent subscribers select `null`, allocate no transfer event/future/deadline timer, and do not create the separate transfer dispatcher. New source-gap frame buffering is used only when a participant requests source release. This review establishes absence of a new per-PLAY-packet coordination hook; it does not turn the noisy timing comparison into a numerical zero-overhead claim.

## Reproduction commands

Example ordinary PLAY command for a single mode/window (run from either checkout):

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 100000 -Warmup 10000 -Payload 4096 -Repeats 5 -Window 1 -Mode post-transfer
```

Change `-Window` to `16` and `-Mode` to `initial` for the other cases. The mode only changes whether transfer setup is performed before measurement; transfer/login/warmup remain excluded from PLAY timing. The paired runner alternates baseline/candidate order inside each pair, uses fresh JVMs, covers all four mode/window combinations, and saves complete output under `%TEMP%`:

```powershell
.\benchmarks\run-play-paired.ps1 -BaselineRepository ..\MoonBridge-baseline
```
