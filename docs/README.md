# StrataProxy Documentation

This directory contains operator-facing documentation. Start with the operations manual when deploying or maintaining a StrataProxy instance.

## Recommended Reading Order

1. [Operations Manual](operations-manual.md): end-to-end setup, configuration, startup, Admin CLI, routing, observability, deployment, and troubleshooting.
2. [Zstd Compression Tuning](compression-zstd.md): optional modded-client Zstd codec, sample collection, dictionary training, and rollout guidance.
3. [Deployment Notes](../deployment/README.md): systemd, container, JVM sizing, and production runtime assets.
4. [Performance Profiles](../deployment/performance/README.md): repeatable smoke and acceptance profiles.
5. [Prometheus Assets](../deployment/observability/prometheus/README.md): alert rules and Prometheus integration.
6. [Grafana Assets](../deployment/observability/grafana/README.md): dashboard packaging notes.

Chinese documentation is under [zh-CN](zh-CN/README.md).

## Document Purpose

| Document | Audience | Purpose |
| --- | --- | --- |
| `README.md` at repository root | Developers and reviewers | Project overview, module list, key feature notes, verification evidence |
| `docs/operations-manual.md` | Operators | How to configure, start, operate, observe, and troubleshoot the proxy |
| `docs/compression-zstd.md` | Operators tuning modded traffic | How to collect samples, train dictionaries, and evaluate Zstd |
| `deployment/README.md` | Production deployers | Host/container/service setup and JVM sizing |
| `deployment/performance/README.md` | Performance testers | How to run and record acceptance evidence |
