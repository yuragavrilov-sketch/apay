# AsiaPay Caching Proxy

Caching proxy for `GET /v1/tkbapp/providers` from `api.asiapay.asia`.

## Run locally

Copy env and set the token:

    cp .env.example .env
    # edit .env and put real ASIAPAY_TOKEN

    docker compose up --build

Then:

    curl http://localhost:8080/v1/tkbapp/providers

## Endpoints

- `GET /v1/tkbapp/providers` — proxied, cached (SWR).
- `GET /actuator/health` — liveness/readiness.
- `GET /actuator/prometheus` — metrics.

## Cache behavior

- **Fresh (<1h):** served from Redis, no upstream call.
- **Stale (≥1h):** served from Redis, async refresh triggered.
- **Miss + upstream down:** HTTP 503.
- **Upstream 4xx:** status and body proxied as-is.
- **Redis down:** fail-open, direct upstream call.

## Prometheus scrape example

```yaml
scrape_configs:
  - job_name: asiapay-proxy
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["asiapay-proxy:8080"]
```

## Tests

    mvn test

Integration tests require Docker (Testcontainers launches Redis).
