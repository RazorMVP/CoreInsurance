# Core Insurance Observability Assets

These assets provide the minimum production monitoring pack for the backend API.
They are intentionally infrastructure-neutral so they can be imported into the
deployment platform when the live environment is provisioned.

## Files

| File | Purpose |
| --- | --- |
| `prometheus-alerts.yml` | Prometheus alert rules for API availability, readiness, HTTP errors, latency, database pool saturation, and migration failures. |
| `grafana-dashboard-coreinsurance.json` | Grafana dashboard starter for API health, request volume, latency, JVM memory, datasource pool usage, and workflow/integration watch points. |

## Required Labels

The alert rules expect the backend scrape target to use:

```yaml
job: cia-api
```

The readiness probe alert expects a blackbox or equivalent probe target with:

```yaml
job: cia-readiness
```

If the live monitoring stack uses different labels, update the selectors before
enabling the rules.

## Go-Live Notes

- Import `prometheus-alerts.yml` into the production Prometheus rule loader.
- Import `grafana-dashboard-coreinsurance.json` into the production Grafana
  folder used for Core Insurance operations.
- Connect alert notifications to the incident channel before first live traffic.
- Tune thresholds after the first controlled load test; do not lower alert
  severity by changing application code.
