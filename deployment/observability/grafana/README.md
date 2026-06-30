# Grafana Dashboard

Import `strataproxy-overview.json` into Grafana and select your Prometheus datasource.

The dashboard covers:

- active connections and registered servers
- event-loop delay and direct memory
- per-server capacity
- global and per-server bandwidth
- compression savings and CPU cost
- packet anomaly rate
- relay backpressure
- route/backend failure rate

Keep dashboard variables low-cardinality. The StrataProxy metric surface intentionally bounds packet IDs, custom payload channels, and anomaly metadata to avoid expensive Prometheus series growth.
