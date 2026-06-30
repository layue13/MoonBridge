# Performance Profiles

This directory defines repeatable performance profiles for StrataProxy release validation.

Profiles are intentionally versioned as JSON so CI, load-test runners, and operators can record comparable results without copying ad hoc commands from documentation.

## Profiles

- `profiles/smoke.json`: small local sanity profile for CI and developer machines.
- `profiles/acceptance-linux-native-java25.json`: production-scale profile for Linux native Netty transport, Java 25, 10k idle connections, and 2k active traffic connections.
- `profiles/compression-rewrite-linux-native-java25.json`: focused profile for live compression rewrite throughput and split compressed-frame buffering.

## Result Recording

Use `profile-result-template.json` to capture evidence after a run. A complete result should include:

- machine shape, OS, Java version, kernel limits, and JVM options
- exact StrataProxy version and git revision when available
- proxy config hash
- `strataproxy-query` command output JSON
- selected `/metrics`, `/overview`, and `strataproxy-admin slo` outputs
- pass/fail conclusion against the profile gates

Do not mark a profile as passed from partial command output. The result must include the configured gates and the observed values used to evaluate them.

## Example

```powershell
.\gradlew.bat --no-daemon :proxy-app:installDist :proxy-query:installDist :proxy-admin-cli:installDist
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 load-suite --profile acceptance --virtual-host play.example.net --parallelism 512 --json
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 slo --require-ready --max-event-loop-delay-ms 5 --max-rejected 0 --max-anomalies 0
```

For Linux hosts, raise file descriptors before running the acceptance profile:

```bash
ulimit -n 1048576
```

The systemd profile already sets `LimitNOFILE=1048576`.
