# Player command interception screening

Run date: 2026-09-26 (Asia/Shanghai). Local Windows loopback, synthetic Minecraft 1.7.10 PLAY echo, four connections, 4,096-byte bodies, 500 warmup and 5,000 measured exchanges per connection, three repetitions, in-flight window 16. Each command started a fresh benchmark JVM. The direct and proxy phases in each command shared the JVM and fake backend. The only proxy configuration difference was whether the frontend command interceptor was installed; no command packets were sent.

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 5000 -Warmup 500 -Payload 4096 -Repeats 3 -Window 16 -CommandInterceptor 1
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 5000 -Warmup 500 -Payload 4096 -Repeats 3 -Window 16 -CommandInterceptor 0
```

| Interceptor | Proxy roundtrips/s, rounds 1–3 | Proxy p95 latency, ms | Correct echoes |
| --- | --- | --- | --- |
| Enabled | 69,444; 65,450; 92,875 | 1.773; 1.951; 0.853 | 60,000 / 60,000 |
| Disabled | 73,820; 84,262; 96,942 | 1.450; 1.241; 0.768 | 60,000 / 60,000 |

The ranges overlap, though all three enabled rounds were slower in this run. Separate JVM runs and visible throughput variation prevent attributing that difference to the interceptor. This short screening run does not resolve a small overhead or establish target-pack capacity. It verifies ordinary framed traffic remained correct with the interceptor installed under these conditions. Raw output: [enabled](2026-09-26-command-interceptor-on.txt), [disabled](2026-09-26-command-interceptor-off.txt).
