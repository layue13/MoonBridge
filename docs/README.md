# StrataProxy Documentation

This is the user-facing documentation index. If you have used BungeeCord, Waterfall, or Velocity before, start with the short path below: install, edit YAML, start the proxy, then manage servers with the Admin CLI.

Chinese documentation is under [zh-CN](zh-CN/README.md).

## Start Here

1. [Quick Start](quick-start.md): build the proxy, edit the first backend, start it, and check that it answers.
2. [Configuration Guide](configuration.md): every important YAML section explained with copyable examples.
3. [Command Reference](commands.md): Admin CLI and query/load-test commands grouped by task.
4. [Zstd Compression Tuning](compression-zstd.md): optional modded-client Zstd codec, sample collection, dictionary training, and rollout guidance.
5. [Operations Manual](operations-manual.md): full production operations, observability, troubleshooting, and acceptance checks.

## Common Tasks

| I want to... | Read |
| --- | --- |
| Run one proxy in front of one backend | [Quick Start](quick-start.md) |
| Add or remove backend servers while the proxy is running | [Command Reference: Backend Servers](commands.md#backend-servers) |
| Understand what each config key does | [Configuration Guide](configuration.md) |
| Use Velocity or Bungee-style IP forwarding | [Configuration Guide: Forwarding](configuration.md#forwarding) |
| Expose the Admin API safely | [Configuration Guide: Admin API](configuration.md#admin-api) |
| Tune compression for modded traffic | [Zstd Compression Tuning](compression-zstd.md) |
| Deploy with systemd or a container | [Deployment Notes](../deployment/README.md) |
| Run smoke or acceptance load checks | [Performance Profiles](../deployment/performance/README.md) |
| Add Prometheus or Grafana | [Prometheus](../deployment/observability/prometheus/README.md) and [Grafana](../deployment/observability/grafana/README.md) |

## Document Map

| Document | Purpose |
| --- | --- |
| [Quick Start](quick-start.md) | First successful local or staging run |
| [Configuration Guide](configuration.md) | Practical YAML configuration reference |
| [Command Reference](commands.md) | Operator command cookbook |
| [Plugins and In-Game Commands](plugins.md) | Player commands and plugin jar entry points |
| [Operations Manual](operations-manual.md) | Complete production runbook |
| [Zstd Compression Tuning](compression-zstd.md) | Minecraft-specific Zstd and dictionary workflow |
| [Deployment Notes](../deployment/README.md) | Linux service, container, JVM, and release bundle notes |
