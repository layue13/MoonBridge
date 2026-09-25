# Online cipher buffer screening — 2026-09-25

Source baseline: `e0be285` on `rewrite/greenfield`. Environment: Windows NT 10.0.26200.0, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1, Netty 4.2.2.Final. One JVM per payload size; each round alternates the order of the array and direct `ByteBuffer` paths. Both paths use the same AES/CFB8 key, IV, reused pooled direct input, and pooled direct output per operation. The input has deterministic bytes; checksums matched for every measured round. `allocatedBytes/op` is the current thread's allocation counter, including JCE internals.

| Payload | Iterations per path | Arrays MiB/s, rounds 2–4 | Direct ByteBuffer MiB/s, rounds 2–4 | Arrays allocated bytes/op | Direct allocated bytes/op |
| ---: | ---: | --- | --- | ---: | ---: |
| 256 B | 50,000 | 56.6 / 54.8 / 56.2 | 56.8 / 58.1 / 57.3 | 678 | 614 |
| 4 KiB | 12,000 | 58.9 / 60.0 / 59.9 | 59.2 / 59.3 / 60.8 | 8,294–8,296 | 8,294 |
| 64 KiB | 1,000 | 61.1 / 60.3 / 59.1 | 61.1 / 62.0 / 62.3 | 131,173–131,182 | 69,670–69,688 |

Commands after `:proxy-core:installDist` and compiling `benchmarks/CipherBenchmark.java` against the distribution libraries:

```powershell
java -cp 'benchmarks/build;proxy-core/build/install/strataproxy/lib/*' dev.strataproxy.core.session.CipherBenchmark 256 50000 5000 4
java -cp 'benchmarks/build;proxy-core/build/install/strataproxy/lib/*' dev.strataproxy.core.session.CipherBenchmark 4096 12000 2000 4
java -cp 'benchmarks/build;proxy-core/build/install/strataproxy/lib/*' dev.strataproxy.core.session.CipherBenchmark 65536 1000 200 4
```

The direct path reduced allocation for the small and large inputs without a clear throughput regression in these rounds. At 4 KiB, allocation and throughput were similar. This is a single-machine synthetic cipher comparison, not a full proxy pipeline, real Forge pack, or production capacity result. Re-run with the target JDK and modpack traffic before claiming an end-to-end gain.
