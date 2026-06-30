# StrataProxy Deployment Profiles

This directory contains production-oriented launch profiles for Linux hosts and containers.

The defaults target large modded networks:

- Java 25 runtime
- ZGC
- fixed heap sizing
- explicit direct-memory budget for Netty
- high file-descriptor limit
- config-relative runtime data under `data/`
- non-root container runtime

## systemd

Install an `installDist` build under `/opt/strataproxy`:

```bash
./gradlew --no-daemon :proxy-app:installDist
sudo install -d -o strataproxy -g strataproxy /opt/strataproxy
sudo cp -a proxy-app/build/install/strataproxy/. /opt/strataproxy/
sudo install -d -o strataproxy -g strataproxy /etc/strataproxy /var/lib/strataproxy /var/log/strataproxy
sudo install -m 0640 -o root -g strataproxy deployment/systemd/strataproxy.env /etc/strataproxy/strataproxy.env
sudo install -m 0644 deployment/systemd/strataproxy.service /etc/systemd/system/strataproxy.service
sudo systemctl daemon-reload
sudo systemctl enable --now strataproxy
```

Before exposing the Admin API beyond loopback, set `STRATAPROXY_ADMIN_TOKEN` and configure TLS or mTLS. The systemd profile stores dynamic registry state under `/var/lib/strataproxy` through `STRATAPROXY_DATA_DIR`; the container profile defaults to `/opt/strataproxy/data`.

## Container

Build the image from the repository root:

```bash
docker build -f deployment/container/Containerfile -t strataproxy:local .
docker run --rm \
  --name strataproxy \
  --ulimit nofile=1048576:1048576 \
  -p 25577:25577 \
  -p 127.0.0.1:8080:8080 \
  -e STRATAPROXY_ADMIN_TOKEN=change-me \
  -v strataproxy-data:/opt/strataproxy/data \
  strataproxy:local
```

For host-network deployments, prefer Linux native transport and keep the Admin API bound to loopback or protected by mTLS.

The release distribution carries Netty native transport libraries for Linux x86_64, Linux aarch64, macOS x86_64, and macOS aarch64. Keep `native.enabled: true`, `native.autoDetect: true`, and `network.nativeTransport: true` for automatic epoll/kqueue selection. Windows deployments run on Netty NIO by design; do not set `native.requireNativeTransport: true` on Windows.

## Observability

Packaged observability assets live under `deployment/observability`:

- `prometheus/strataproxy-alerts.yml` contains alert rules for target availability, backend readiness, route/backend failures, event-loop delay, heap/direct-memory pressure, rejected connection spikes, packet anomalies, custom payload floods, and relay backpressure.
- `grafana/strataproxy-overview.json` contains a Prometheus-backed dashboard for the main proxy health and performance surface.

Prometheus scrape example:

```yaml
scrape_configs:
  - job_name: strataproxy
    static_configs:
      - targets: ["127.0.0.1:8080"]
```

Keep the Admin API on loopback or protect it with TLS/mTLS and a bearer token before exposing `/metrics` to a network scraper.

## JVM Sizing

Start with:

```text
JAVA_OPTS=-Xms2g -Xmx2g -XX:MaxDirectMemorySize=2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError
```

Increase heap when Admin API reports high retained JVM heap. Increase direct memory when Prometheus reports pooled direct memory close to the configured ceiling or when large compressed payload bursts are expected. Keep direct memory and heap below the cgroup or host memory limit with operating-system headroom.
