# Architecture: spring-resilient-client

A price API that calls a quote provider over HTTP and stays fast and honest while the provider is slow, failing or silent. The goal is not zero errors: it is bounded latency, no retry storm against a sick provider, and a clear signal when an answer is stale.

## Components and what each owns

Names follow the lesson's diagram.

| Lesson diagram | Class | Layer / module | Owns |
| --- | --- | --- | --- |
| Load tool | `loadgen` module (`LoadRunner`, `LoadMain`) | `loadgen` | an open-loop load at a fixed rate; p50, p99, error rate, stale share |
| Fake provider | `FakeProvider` (plain JDK `HttpServer`) | `provider` | the modes normal, slow, 503 and hang; its own counters of calls received and calls in flight; `/admin/*`, `/metrics` |
| Price API | `PriceController`, `CatalogController`, `ProviderExceptionHandler`, `TraceIdFilter` | `price-service`, interfaces | HTTP, validation, problem documents (504 for a timeout, 503 with `Retry-After` for any other provider failure), the `X-Trace-Id` header |
| Quote cache | `QuoteCache` port, `InMemoryQuoteCache` | domain port, infrastructure | the last good quote per SKU, 60 seconds from the write, bounded size |
| Concurrency limit | `@ConcurrencyLimit(policy = REJECT)` on `ProviderCall.attempt` | infrastructure | at most N provider calls in flight; the rest are refused at once |
| Bounded retry | `@Retryable` on `ProviderCall.attempt` | infrastructure | at most two retries, 503 only, exponential delay with jitter |
| RestClient | `ProviderClientConfig.providerRestClient` | infrastructure | 1 second connect timeout, 2 second read timeout, Boot's builder so metrics and traces cover every call |
| Dashboard | Grafana stack (`grafana/otel-lgtm`) fed by OTLP | compose | metrics and traces of the price service |
| (use case) | `PriceService` | application | try the provider, remember the quote, or fall back to the last good one labeled stale |
| (port adapters) | `HttpQuoteSource`, `UnprotectedQuoteSource` | infrastructure | name the outcome of a call for the domain: `ProviderTimeout`, `ProviderUnavailable`, `ProviderBusy` |

The provider is deliberately not a Spring application (see [ADR 0001](docs/adr/0001-provider-is-not-spring.md)), so the module list is: `provider` and `loadgen` (plain Java) and `price-service` (Spring Boot, layered `domain`, `application`, `infrastructure`, `interfaces`, with the dependency rule enforced by `ArchitectureTest`).

## Data flow

Drawn versions of this figure and of the walk-through: [the architecture](docs/diagrams/architecture.svg) and [one run](docs/diagrams/flow.svg).

```mermaid
flowchart LR
    load([Load tool])
    subgraph price["price-service"]
        api[PriceController]
        svc[PriceService]
        source[HttpQuoteSource]
        call["ProviderCall.attempt<br/>@Retryable (503 only, 2 retries)<br/>@ConcurrencyLimit (REJECT)"]
        rc[RestClient<br/>1 s connect, 2 s read]
        cache[(InMemoryQuoteCache<br/>60 s from write)]
    end
    prov[Fake provider<br/>normal / slow / 503 / hang]
    dash[(Grafana, Tempo, Prometheus)]

    load -->|GET /price/sku| api --> svc --> source --> call --> rc -->|GET /quotes/sku| prov
    svc -->|fresh quote: remember| cache
    svc -. provider failed: last good quote, stale=true .-> cache
    call -. metrics and traces .-> dash
    api -->|200 fresh, 200 stale, 503, 504| load
```

### One price request, step by step

1. `PriceController` validates the SKU and calls `PriceService.price`.
2. `HttpQuoteSource.fetch` calls `ProviderCall.attempt`, a separate bean, through its proxy. The proxy first asks the concurrency limit for a slot. If all N slots are taken, it throws at once (`InvocationRejectedException`) and no provider call is made.
3. With a slot, the attempt sends `GET /quotes/{sku}` through the `RestClient`. A 200 gives a `Quote`.
4. A 503 is the one retryable failure: the retry interceptor waits 100 ms (plus up to 50 ms of jitter), then 200 ms, then stops after two retries. The slot is taken once, before the first attempt, and held while the retries run (the concurrency limit wraps the retry loop); each attempt is counted by the provider independently, so one price request holds one slot and makes at most three provider calls.
5. A timeout (`HttpTimeoutException` inside `ResourceAccessException`) is a different exception type, so it is not retried: the request has already spent the read timeout, and a retry would add load to a provider that is struggling.
6. `HttpQuoteSource` names the final outcome (`ProviderTimeout`, `ProviderUnavailable` or `ProviderBusy`) and counts it.
7. `PriceService` remembers a fresh quote in the cache and answers `stale: false`. On a `ProviderFailure` it asks the cache for the last good quote; if one is younger than 60 seconds it answers `stale: true` with its age, otherwise it raises `NoPriceAvailable`, which becomes a 504 (the failure was a timeout) or a 503 with `Retry-After` (anything else).

## Decisions and trade-offs

| Decision | Why | Cost |
| --- | --- | --- |
| Both timeouts on every call, validated at startup (`ProviderProperties`) | A slow dependency can then only cost a fixed amount of latency; a zero or negative timeout stops the application | a legitimately slow provider needs a configuration change, not a longer wait |
| Retry 503 only, never a timeout | A 503 is the provider shedding load and likely to clear fast; a retried timeout adds load to something already overloaded; naming only `HttpServerErrorException.ServiceUnavailable` makes this true by construction | a transient timeout is not retried |
| Concurrency limit with the `REJECT` policy, not `BLOCK` ([ADR 0002](docs/adr/0002-reject-not-block.md)) | Waiting for a slot would make latency unbounded while the provider hangs: at 100 requests a second and 2 second calls, a limit of 10 serves 5 a second and the queue grows without bound. Rejecting keeps every answer fast, and the cache can still answer | requests beyond the limit get a stale answer or a 503 even though the provider might have recovered a moment later |
| The cache is a fallback, not a read-through shortcut | The lesson's expected answer for a recently priced SKU with the provider down is `stale: true`, which a fresh-hit shortcut would hide; reading the cache first would also keep serving an old price for 60 seconds after the provider recovered | every request calls the provider while it is healthy |
| Expiry measured from the write | A quote that is read often must still age out; `InMemoryQuoteCacheTest.readingDoesNotExtendTheLifeOfAQuote` | none |
| Virtual threads (`spring.threads.virtual.enabled=true`) | Many blocking calls to a slow provider no longer tie up platform threads, so the catalog endpoint keeps its latency | none at this scale |
| `@Retryable` and `@ConcurrencyLimit` from `org.springframework.resilience.annotation`, enabled by `@EnableResilientMethods` | Framework 7 ships them; no separate Spring Retry library | they act only through the proxy, so `ProviderCall` is its own bean and `ArchitectureTest` forbids calling an annotated method from its own class |
| A `price.protection=none` variant (`UnprotectedQuoteSource`, `NoQuoteCache`, a client without timeouts) | The lesson's before-and-after table needs a "before" that is the same service without the protections | a small amount of code that must never run in production; it is selected only by that property |
| Java 25, Spring Boot 4.1 | The lesson names no Java version; the Framework 7 resilience annotations need the Boot 4 generation | none |

## Failure modes the lesson teaches, and where the code shows them

| Failure | What the provider does | What the service does | Proved by |
| --- | --- | --- | --- |
| Provider hangs | accepts the connection, answers nothing for 10 seconds | the 2 second read timeout cuts the call; stale answer if one is remembered, else 504 `Provider timed out`; exactly one provider call | `PriceServiceBehaviorIT.doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout`, `make run` step 1 |
| Provider answers 503 | 503 at once | up to three calls (1 + 2 retries), jittered delays; then stale or 503 | `PriceServiceBehaviorIT.retriesAtMostThreeCallsTotalOn503` (counted on the provider side), `ResilienceAnnotationsTest` |
| Provider is slow past the read timeout | answers 200 after 5 seconds | not retried: one call; timeout path as above | `PriceServiceBehaviorIT.aSlowProviderBeyondTheTimeoutIsNotRetriedEither` |
| Thread and connection pile-up | slow | at most N calls in flight, the rest refused immediately; the provider's own `maxInFlight` never exceeds N; the catalog endpoint answers during the storm | `PriceServiceBehaviorIT.inFlightProviderCallsNeverExceedTheLimitWhileOtherEndpointsKeepAnswering`, `ResilienceAnnotationsTest` |
| Provider is down, SKU recently priced | 503 or silence | 200 with `stale: true` and `ageSeconds` | `PriceServiceBehaviorIT.aRecentlyPricedSkuIsServedStaleWhenTheProviderIsDownAndANeverPricedOneIs503` |
| Provider is down, SKU never priced, or quote older than 60 s | 503 or silence | 503 `Provider unavailable` with `Retry-After` (504 if the cause was a timeout) | same test, and `aQuoteOlderThanSixtySecondsIsNotServed` |
| Retry storm | | not possible: at most two retries, 503 only, jittered, and the whole retry loop runs inside one concurrency slot | the call-count tests above |

## Limits of the teaching build

- Metrics, traces and a dashboard are real but local: one Grafana container, no alerting, no retention.
- One provider and one instance of the service: the concurrency limit is per instance, so ten instances can put ten times the limit on the provider. A shared limit needs a gateway or a token service.
- No circuit breaker (the lesson's stretch): the limit and the timeouts bound the damage, but a failing provider is still asked on every request.
- The cache is in memory and per instance; a restart empties it, and two instances may disagree about a price.
- The benchmark is a laptop-scale measurement: numbers depend on the machine and on what else runs on it.

## How to extend it

- Add a circuit breaker in `HttpQuoteSource` (or a resilience library behind the `QuoteSource` port) and show, with the benchmark, the provider recovering without a flood of calls when it comes back.
- Replace the annotations with a programmatic `RetryTemplate` and compare how the policies read.
- Build the service as a native image and compare startup and memory under the same load.
