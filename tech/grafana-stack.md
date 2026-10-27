# Grafana, Prometheus and Tempo (the grafana/otel-lgtm image)

## What it is

- **Prometheus** stores metrics (numbers over time) and answers queries written in PromQL.
- **Tempo** stores traces and finds one by its trace id.
- **Grafana** is the web interface: dashboards of charts, built from queries to Prometheus, Tempo and others.
- An **OTLP receiver** accepts metrics and traces pushed over the OpenTelemetry protocol.

`grafana/otel-lgtm` is one container image that bundles all of these for local use ("LGTM" stands for Loki, Grafana, Tempo, Mimir; the image uses Prometheus for metrics).

## Why this repository uses it

The lesson's last step needs a dashboard and traces. A single container is the cheapest way to have both on a laptop, with nothing to install beyond Docker.

## Where it appears

| Path | Role |
| --- | --- |
| `docker-compose.yml` (service `otel-lgtm`) | runs the image and publishes its ports |
| `observability/dashboards.yaml` | tells Grafana where to find dashboard files |
| `observability/price-service-dashboard.json` | the dashboard: four charts |
| `scripts/capture-traces.sh` | asks Tempo's HTTP API for a trace by id |
| `.env.example` | the host ports: Grafana 13000, OTLP 14318, Tempo 13200, Prometheus 19090 |

## How it works underneath

1. The price service pushes metrics every 10 seconds and spans as they finish, over HTTP, to port 4318 inside the container.
2. The image's built-in collector hands metrics to Prometheus and spans to Tempo.
3. Grafana has both as **data sources**. A dashboard panel holds a query and a chart type.
4. **Provisioning** means Grafana reads files at startup instead of you clicking: a provider file (`dashboards.yaml`) names a folder, and every JSON file there becomes a dashboard.
5. Tempo's HTTP API (`/api/traces/<id>`) returns the spans of a trace as JSON.

## Walkthrough of this repository's use

```yaml
  otel-lgtm:
    image: grafana/otel-lgtm:latest
    ports:
      - "${GRAFANA_PORT:-13000}:3000"
      - "${OTLP_HTTP_PORT:-14318}:4318"
      - "${TEMPO_PORT:-13200}:3200"
      - "${PROMETHEUS_PORT:-19090}:9090"
    volumes:
      - ./observability/dashboards.yaml:/otel-lgtm/grafana/conf/provisioning/dashboards/plainround.yaml:ro
      - ./observability/price-service-dashboard.json:/var/lib/plainround-dashboards/price-service-dashboard.json:ro
```

- `ports` publishes Grafana (3000 inside, 13000 on the host), the OTLP HTTP receiver (4318, 14318), Tempo's query API (3200, 13200) and Prometheus (9090, 19090).
- The two `volumes` mount files **read-only** (`:ro`): the provisioning file into Grafana's provisioning folder, and the dashboard JSON into the folder the provider file names.

```yaml
apiVersion: 1
providers:
  - name: plainround
    folder: Plainround
    type: file
    disableDeletion: true
    options:
      path: /var/lib/plainround-dashboards
```

`providers:` lists dashboard sources. `type: file` reads JSON files; `options.path` is the folder (`/var/lib/plainround-dashboards`, where compose mounts the JSON); `folder: Plainround` puts the dashboards in a Grafana folder of that name; `disableDeletion: true` stops deletion from the interface.

The dashboard JSON has four time-series panels, each a PromQL query. For example the first:

```json
"title": "Price API latency, p50 and p99 (ms)",
"gridPos": {
  "h": 8,
  "w": 12,
  "x": 0,
  "y": 0
},
"datasource": {
  "type": "prometheus",
  "uid": "prometheus"
},
"fieldConfig": {
  "defaults": {
    "unit": "ms"
  },
  "overrides": []
},
"targets": [
  {
    "refId": "A",
    "expr": "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{uri=\"/price/{sku}\"}[1m])))",
    "legendFormat": "p99"
```

`histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{uri="/price/{sku}"}[1m])))` reads: take the per-second rate of requests in each latency bucket over the last minute, sum the buckets, and compute the value below which 99 percent fall. The other panels chart provider attempts per result, final failures per reason, and cache lookups.

**Status of this part:** the dashboard and its queries were written from the metric names Spring Boot and Micrometer produce, but were not run against a live Grafana in this build (Docker was unavailable at the end). Check the metric names in Prometheus (`http://localhost:19090`) the first time and adjust the queries if a name differs.

## Try it yourself

1. After `make up`: open `http://localhost:13000` (user `admin`, password `admin`, the local container's default) and choose Dashboards, then the Plainround folder.
2. Open Prometheus at `http://localhost:19090` and run `price_provider_attempts_total`.
3. Make traffic: `make run`, then look at the charts move.
4. `curl -s localhost:13200/api/traces/<trace id>` returns a trace you got from `X-Trace-Id`.

## Mistakes people make

- Expecting data before the first push: the service pushes every 10 seconds.
- Using `localhost` as the collector address inside a container: use the service name (`otel-lgtm`).
- Mounting the dashboard to a folder the provider file does not name.
- Leaving the default Grafana password on something reachable from outside your machine.

## Check yourself

1. Which component stores metrics? *Prometheus.*
2. Which stores traces? *Tempo.*
3. What is provisioning? *Grafana reading its setup from files at startup.*
4. What does `histogram_quantile(0.99, ...)` compute? *The 99th percentile.*
5. Why does the service reach the collector at `otel-lgtm:4318`? *Inside the compose network, services are reachable by name.*

## Further reading

- Grafana documentation, provisioning dashboards: https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards
- Prometheus querying basics: https://prometheus.io/docs/prometheus/latest/querying/basics/
- Grafana Tempo HTTP API: https://grafana.com/docs/tempo/latest/api_docs/
- The `grafana/otel-lgtm` image: https://github.com/grafana/docker-otel-lgtm
