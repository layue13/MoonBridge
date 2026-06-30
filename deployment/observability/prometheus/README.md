# Prometheus Rules

Load `strataproxy-alerts.yml` into Prometheus or a compatible rule manager.

The rules assume the StrataProxy Admin API `/metrics` endpoint is scraped with `job="strataproxy"`:

```yaml
scrape_configs:
  - job_name: strataproxy
    static_configs:
      - targets: ["127.0.0.1:8080"]
```

Tune thresholds to your host and expected modpack behavior. The default rules intentionally focus on availability, event-loop delay, memory pressure, route/backend failures, relay backpressure, rejected connection spikes, and packet anomaly rule hits.
