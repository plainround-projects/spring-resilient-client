# Micrometer metrics and OpenTelemetry traces

## What it is

- A **metric** is a number measured over time: a counter that only goes up (requests served), a gauge (calls in flight now), or a timer with a distribution (how long calls took). **Micrometer** is the Java library that records metrics; Spring Boot wires it in.
- A **trace** is the record of one request's path through the system. It is made of **spans**: each span is one step with a start, an end and a name (a server span for the incoming request, a client span for each call to the provider). All spans of a request share a **trace id**. **OpenTelemetry** (OTel) is the open standard for creating and exporting traces and metrics. **OTLP** is its network protocol.

## Why this repository uses it

The lesson's last step is "prove it on the dashboard": show retries and stale answers in metrics, and show one trace of a request that retried and one that was served stale. Claims about p99 latency need numbers, not log lines.

## Where it appears

| Path | Role |
| --- | --- |
| `price-service/pom.xml` | `spring-boot-starter-opentelemetry` (tracing and OTLP export) and `micrometer-registry-prometheus` |
| `infrastructure/ProviderCall.java` | the service's own metrics: attempts per result, a timer of provider calls |
| `infrastructure/HttpQuoteSource.java` | final failures per reason |
| `infrastructure/InMemoryQuoteCache.java` | cache hits and misses |
| `interfaces/web/TraceIdFilter.java` | puts the trace id in the `X-Trace-Id` response header |
| `application.properties` | sampling and export addresses |
| `scripts/capture-traces.sh` | fetches three traces by id |

## How it works underneath

1. A `MeterRegistry` is the place meters are created: `meters.counter("name", "tag", "value").increment()`. **Tags** split one metric into series (`result=success`, `result=unavailable`).
2. Spring Boot adds automatic meters: HTTP server requests (a timer per URL and status), JVM memory, threads.
3. Boot exports meters two ways here: as text at `/actuator/prometheus` for a scraper to pull, and by **pushing** them every `step` (10 seconds) over OTLP to a collector.
4. Tracing: when a request arrives, Boot starts a span and makes it the **current span** of the thread. The `RestClient` call to the provider creates a child span, and each retry attempt is its own child span, all under one trace id. The ids are also put in the log context (see `structured-logging.md`).
5. **Sampling probability** `1.0` means every trace is kept. In production a lower value saves cost.
6. A **percentile histogram** (`publishPercentileHistogram`) records bucket counts, so the backend can compute p50 and p99.

## Walkthrough of this repository's use

```properties
management.logging.export.otlp.enabled=false
management.tracing.sampling.probability=1.0
management.opentelemetry.tracing.export.otlp.endpoint=${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:14318/v1/traces}
management.otlp.metrics.export.url=${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:http://localhost:14318/v1/metrics}
management.otlp.metrics.export.step=10s
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

- `management.tracing.sampling.probability=1.0` keeps all traces.
- `management.opentelemetry.tracing.export.otlp.endpoint=${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:14318/v1/traces}` is where spans are sent; the compose file points it at the Grafana container.
- `management.otlp.metrics.export.url=...` and `step=10s` push metrics every 10 seconds.
- `management.metrics.distribution.percentiles-histogram.http.server.requests=true` records histograms for every request, which is what makes a p99 chart possible.
- `management.logging.export.otlp.enabled=false`: logs go to standard output only.

The service's own meters:

```java
private void record(Timer.Sample sample, String result) {
    meters.counter("price.provider.attempts", "result", result).increment();
    sample.stop(
            Timer.builder("price.provider.call")
                    .tag("result", result)
                    .publishPercentileHistogram()
                    .register(meters));
}
```

`meters.counter("price.provider.attempts", "result", result).increment()` counts every attempt by outcome (`success`, `unavailable`, `io`, `error`); retries show as extra `unavailable` attempts. `Timer.builder("price.provider.call")...register(meters)` times each attempt, and `sample.stop(...)` records the duration. In Prometheus text these become `price_provider_attempts_total` for the counter and, for the timer, `price_provider_call_seconds_bucket`, `price_provider_call_seconds_sum` and `price_provider_call_seconds_count`.

```java
public Quote fetch(String sku) {
    try {
        return call.attempt(sku);
    } catch (RuntimeException exception) {
        ProviderFailure failure = ProviderErrors.translate(exception);
        meters.counter("price.provider.failures", "reason", ProviderErrors.reason(failure))
                .increment();
        throw failure;
    }
}
```

`price.provider.failures` counts the final outcome of a logical call by reason (`timeout`, `unavailable`, `busy`).

The trace id for callers:

```java
Span span = tracer.currentSpan();
if (span != null) {
    response.setHeader(HEADER, span.context().traceId());
}
chain.doFilter(request, response);
```

`tracer.currentSpan()` is the span of the current request; `span.context().traceId()` is its 32-character hex id, copied into `X-Trace-Id`.

The test that checks both:

```java
void metricsAndTraceIdsAreExposed() {
    ResponseEntity<String> price = get("/price/sku-metrics");
    provider.mode(ProviderMode.FAIL503);
    get("/price/sku-metrics-2");

    String prometheus = get("/actuator/prometheus").getBody();

    assertThat(price.getHeaders().getFirst("X-Trace-Id")).matches("[0-9a-f]{32}");
    assertThat(prometheus)
            .contains(
                    "price_provider_attempts_total{",
                    "result=\"success\"",
                    "result=\"unavailable\"");
    assertThat(prometheus)
            .contains("price_provider_failures_total{", "price_provider_call_seconds_bucket");
    assertThat(prometheus).contains("price_cache_lookups_total{");
}
```

It reads `/actuator/prometheus` and asserts the service's meters are there, and asserts the header matches `[0-9a-f]{32}`. In tests the export is switched off (`management.tracing.export.enabled=false`) so nothing needs a collector.

**Every production class that records a metric or reads the current trace:**

| File | What it is |
| --- | --- |
| `price-service/src/main/java/dev/plainround/price/infrastructure/HttpQuoteSource.java` | Adapter: the quote port over the protected provider call. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/InMemoryQuoteCache.java` | Remembers the last good quote per SKU in memory. |
| `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderCall.java` | One protected attempt to ask the provider for a price. |
| `price-service/src/main/java/dev/plainround/price/interfaces/web/TraceIdFilter.java` | Puts the id of the request's trace in the `X-Trace-Id` response header, so a caller (or the README's trace capture) can find exactly this request in the trace backend. |

## Try it yourself

1. `curl -s localhost:18104/actuator/prometheus | grep price_provider` lists the service's meters.
2. `curl -si localhost:18104/price/sku-1 | grep -i x-trace-id` prints a trace id.
3. After `make up` and some traffic, open Grafana (`http://localhost:13000`) and search Tempo for that trace id.
4. `make traces` fetches three traces and writes a text summary of their spans to `docs/evidence/`.

## Mistakes people make

- Putting an unbounded value (a user id, a SKU) in a tag: it creates a series per value and exhausts memory.
- Sampling everything in production.
- Looking at averages: the 99th percentile shows the waiting.
- Reading a metric without knowing its unit: Boot's OTLP export defaults to milliseconds for timers, while the Prometheus text uses seconds.

## Check yourself

1. What is a span? *One timed step in a request's path.*
2. What do all spans of one request share? *The trace id.*
3. What is a tag for? *Splitting one metric into series.*
4. Why record percentile histograms? *To compute p50 and p99.*
5. Why is the trace id returned in a header? *So a caller can find that exact trace.*

## Further reading

- Micrometer documentation: https://docs.micrometer.io/micrometer/reference/
- Spring Boot 4.1.2, "Observability": https://docs.spring.io/spring-boot/4.1.2/reference/actuator/observability.html
- OpenTelemetry concepts: https://opentelemetry.io/docs/concepts/
