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

Use the bundled runner to execute a profile and write a complete evidence file:

```bash
python3 deployment/performance/run_profile.py acceptance-linux-native-java25 \
  --query-bin ./proxy-query/build/install/strataproxy-query/bin/strataproxy-query \
  --admin-bin ./proxy-admin-cli/build/install/strataproxy-admin/bin/strataproxy-admin \
  --admin-url http://127.0.0.1:8080 \
  --config ./proxy-app/build/install/strataproxy/config/strataproxy-production.yml
```

The runner executes each profile command, embeds JSON output, fetches `/metrics`, `/overview`, and `/native-capabilities`, evaluates profile gates, and writes `deployment/performance/results/<profile>-<timestamp>.json`. Result files are intentionally ignored by Git; archive them with release or incident evidence instead.

## Example

```powershell
.\gradlew.bat --no-daemon :proxy-app:installDist :proxy-query:installDist :proxy-admin-cli:installDist
python deployment\performance\run_profile.py acceptance-linux-native-java25 --query-bin .\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --admin-bin .\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --admin-url http://127.0.0.1:8080
```

The acceptance latency gate requires an echo backend that returns each received Minecraft frame unchanged for the `traffic-latency-acceptance` command.

For Linux hosts, raise file descriptors before running the acceptance profile:

```bash
ulimit -n 1048576
```

The systemd profile already sets `LimitNOFILE=1048576`.
