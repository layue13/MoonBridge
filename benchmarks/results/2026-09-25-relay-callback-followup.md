# Raw relay write callback screening

Run date: 2026-09-25 (Asia/Shanghai). Base revision: `56eeb1b` on `rewrite/greenfield`. Windows 11 Enterprise, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1. All runs used four clients, 10,000 measured and 1,000 warmup echoes per client, 4,096-byte PLAY bodies, and five alternating direct/proxy rounds. Each command rebuilt the installed proxy distribution. Every measured echo matched the request; there were no errors.

The candidate handled a peer write completion immediately if the source and peer shared an event loop, rather than always posting a task to the source loop. `RawRelayTest` passed with the candidate. The candidate was then reverted and the Window 1 baseline repeated (A/B/A). The repository retains the original callback behavior.

| Run | In-flight window | Paired proxy/direct throughput ratios, rounds 1–5 | Proxy p95 latency, rounds 3–5 |
| --- | ---: | --- | --- |
| [Baseline](2026-09-25-relay-callback-baseline-window1.txt) | 1 | 0.598, 0.539, 0.562, 0.569, 0.592 | 0.191, 0.183, 0.181 ms |
| [Candidate](2026-09-25-relay-callback-candidate-window1.txt) | 1 | 0.519, 0.679, 0.595, 0.589, 0.593 | 0.129, 0.130, 0.131 ms |
| [Baseline recheck](2026-09-25-relay-callback-baseline-recheck-window1.txt) | 1 | 0.544, 0.688, 0.587, 0.594, 0.593 | 0.132, 0.130, 0.129 ms |
| [Baseline](2026-09-25-relay-callback-baseline-window16.txt) | 16 | 0.814, 0.806, 1.055, 1.046, 1.050 | 0.710, 0.712, 0.725 ms |
| [Candidate](2026-09-25-relay-callback-candidate-window16.txt) | 16 | 0.728, 0.731, 0.864, 1.045, 1.078 | 0.713, 0.711, 0.701 ms |

The first Window 1 baseline ran at a lower direct and proxy rate than both later runs. The candidate and baseline recheck were nearly identical in rounds 3–5, including p95 latency. Window 16 had no consistent paired throughput improvement. The evidence does not justify changing the callback path.

These are synthetic loopback echo results, not backend load observation. They do not test Forge modpack traffic, a real client and server, cross-host networking, or production capacity.
