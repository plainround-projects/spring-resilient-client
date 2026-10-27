# spring-resilient-client

A price service that stays fast and honest while its provider is slow, failing or silent: a timeout on every call, at most two retries and only for a 503, a limit on concurrent provider calls, and the last good quote served with a `stale` label, with metrics and traces to show it.

| | |
| --- | --- |
| **Lesson** | `/prep/spring/project-resilient-client`, "A price service against a sick provider" (`data-structure-explainer/src/content/tracks/spring/17-project-resilient-client.md` in the Plainround app) |
| **Planned hours** | 14 |
| **Level** | Advanced |
| **Versions** | Spring Boot 4.1.2 (Spring Framework 7.0), Java 25 (the current LTS; the lesson names none), Maven 3.9 through the checked-in wrapper |
| **Architecture** | [ARCHITECTURE.md](ARCHITECTURE.md), decisions: [docs/adr](docs/adr), benchmark: [docs/benchmark.md](docs/benchmark.md) |
| **Technology notes** | [tech/README.md](tech/README.md): one note per technology used here, written for a complete beginner |
| **Author** | Sumit Srivastava |

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 25 (Temurin 25.0.2 tested; CI uses Temurin 25) | build, tests, the load generator |
| Docker with Compose v2 | 24 or newer | `make up` (provider, price service, Grafana stack), `make audit` |
| GNU Make, `curl`, `jq` | any recent | `make run`, `make benchmark`, `make traces` |
| Images | `eclipse-temurin:25` (pinned by digest), `grafana/otel-lgtm` (Grafana, Prometheus, Tempo and an OTLP receiver in one image) | pulled on first use |

No cloud account, no secrets. Host ports (change them in `.env`): provider 18103, price service 18104, Grafana 13000, OTLP 14318, Tempo 13200, Prometheus 19090.

## Setup, run, test, stop

```bash
make up            # build both images, start provider + price service + Grafana stack, wait until healthy
make run           # steps 1, 2, 3, 4 and 5 of the lesson against the running stack, with the provider's own counters
make benchmark     # the before/after table for every provider mode (about 6 minutes) -> docs/benchmark.md
make traces        # a retried trace, a stale trace and a refused trace from Tempo -> docs/evidence/
make test          # all modules: unit, slice and integration tests, coverage gates (no Docker needed)
make lint          # Spotless format check + Checkstyle on every module
make down          # stop everything
```

`make help` lists the rest (`make test-unit`, `make format`, `make audit`, `make docker-build`, `make health`, `make serve`). Grafana is at `http://localhost:13000` after `make up` (user `admin`, password `admin`, a throwaway for a container that lives on your machine only); the dashboard "Price service against a sick provider" is provisioned from `observability/`.

Flip the provider by hand: `curl -X PUT 'localhost:18103/admin/mode?mode=hang'` (modes `normal`, `slow` with `&delayMs=5000`, `fail503`, `hang`; `&failNext=2` fails the next two calls whatever the mode), read its counters with `curl localhost:18103/admin/stats`.

## Project structure

```text
spring-resilient-client/
├── pom.xml, mvnw, .mvn/              parent POM (plugins and versions for all modules), wrapper checked in
├── provider/                         the fake provider: a plain JDK HttpServer, no framework (ADR 0001)
│   ├── src/main/java/dev/plainround/provider/    FakeProvider, ProviderMode, ProviderMain, JsonLog
│   └── src/test/java/...             FakeProviderTest, mirrored by package
├── loadgen/                          the paced load generator: LoadRunner, LoadMain, Report
├── price-service/                    the Spring Boot application
│   ├── src/main/java/dev/plainround/price/
│   │   ├── domain/                   Quote, PriceResult, named failures, ports QuoteSource, QuoteCache, Catalog. Depends on nothing but the JDK.
│   │   ├── application/              PriceService, CatalogService. Depends on domain.
│   │   ├── infrastructure/           ProviderCall (@Retryable, @ConcurrencyLimit), HttpQuoteSource, RestClient config, InMemoryQuoteCache, properties, the "none" variants. Depends on domain.
│   │   └── interfaces/web/           controllers, ProviderExceptionHandler, TraceIdFilter. Nothing depends on it.
│   ├── src/main/resources/application.properties
│   └── src/test/java/dev/plainround/price/   mirrored by package, ArchitectureTest, PriceServiceBehaviorIT
├── observability/                    Grafana provisioning and the dashboard JSON
├── scripts/                          scenario.sh (make run), benchmark.sh, capture-traces.sh, audit.sh
├── docs/                             benchmark.md, evidence/, adr/
├── tech/                                 one note per technology (Java, Spring, SQL, Docker, Bash, ...) for a complete beginner, with the repository\'s own lines explained
├── config/checkstyle/                lint rules and the few documented suppressions
├── provider/Dockerfile, price-service/Dockerfile, docker-compose.yml, Makefile, .github/workflows/ci.yml, .env.example, .editorconfig, .gitignore
└── README.md, ARCHITECTURE.md, CHANGELOG.md, LICENSE
```

The dependency rule for `price-service` (domain depends on nothing; application depends on domain; infrastructure and interfaces depend on both; nothing depends on interfaces) is enforced by `ArchitectureTest`, together with a rule that no class calls an `@Retryable`, `@ConcurrencyLimit` or `@Transactional` method of its own class (a proxy would be skipped).

## Done when: the lesson's checklist and the proof for each line

| # | Done when (from the lesson) | Proof |
| --- | --- | --- |
| 1 | With the provider in "hang" mode, a price request fails in about 2 seconds with a `ProblemDetail` 504, instead of hanging. | `make run` step 1 prints the HTTP code and time (SKU never priced: 504 `Provider timed out` in about 2 s, not the provider's 10 s). In the tests `PriceServiceBehaviorIT.doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout` asserts 504, `application/problem+json`, and a duration of about the (shortened, 500 ms) read timeout. The production timeouts (1 s connect, 2 s read) are validated at startup by `ProviderPropertiesTest` and pinned by `ApplicationConfigurationTest`. A SKU that was priced recently is served stale instead of failing (step 5); the 504 shows for a SKU with nothing remembered. |
| 2 | You have p50, p99 and error rate written down for the healthy provider. | `make run` step 2 and the first rows of `make benchmark` (`docs/benchmark.md`). **Pending capacity**: the numbers are produced by those commands against the container stack. |
| 3 | In "503" mode the provider sees at most three calls per price request, and in "slow" mode it sees no retries at all. | `PriceServiceBehaviorIT.retriesAtMostThreeCallsTotalOn503` (the provider's own counter is 3), `doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout` (hang: 1), `aSlowProviderBeyondTheTimeoutIsNotRetriedEither` (slow: 1); `ResilienceAnnotationsTest.retryableStopsAfterTheConfiguredNumberOfRetriesAndOnlyForTheNamedException` for the annotation on its own; `make run` step 3 repeats it against the containers. |
| 4 | With the provider in "slow" mode, in-flight provider calls never exceed the limit on the provider's own metric, and the price API's other endpoints keep their baseline p99. | `PriceServiceBehaviorIT.inFlightProviderCallsNeverExceedTheLimitWhileOtherEndpointsKeepAnswering` (20 concurrent requests, limit 3: the provider's `maxInFlight` is at most 3, some requests refused at once, the catalog endpoint under 300 ms during the storm); `ResilienceAnnotationsTest.twentyConcurrentCallsAgainstALimitOfThreeNeverShowMoreThanThreeInFlight` (the lesson's own example, 20 calls against a limit of 3, exactly 3). `make run` step 4 prints the provider's `maxInFlight` and the catalog latency against the containers, and the benchmark's last column is the catalog endpoint's p99. |
| 5 | With the provider down, a recently priced SKU returns 200 with `stale: true`, and a never-priced SKU returns a 503 `ProblemDetail`. | `PriceServiceBehaviorIT.aRecentlyPricedSkuIsServedStaleWhenTheProviderIsDownAndANeverPricedOneIs503` (stale with `ageSeconds` 37, 503 `Provider unavailable` with the detail `no price available for sku-never-priced`), `aQuoteOlderThanSixtySecondsIsNotServed`, `staleServingAlsoCoversATimeout`; `InMemoryQuoteCacheTest` (expiry from the write, not the read). |
| 6 | The README has a table of p99 and error rate for each provider mode, before and after, plus two trace screenshots. | The table: [docs/benchmark.md](docs/benchmark.md), written by `make benchmark`. The trace evidence: `make traces` writes text summaries and the raw Tempo JSON to `docs/evidence/` instead of screenshots. **Pending capacity** for both (they need the Docker stack). What is built: metrics (`price_provider_attempts_total`, `price_provider_failures_total`, `price_provider_call_seconds`, `price_cache_lookups_total`, Boot's HTTP server metrics) at `/actuator/prometheus` and over OTLP, traces over OTLP with an `X-Trace-Id` header, and the dashboard JSON. `PriceServiceBehaviorIT.metricsAndTraceIdsAreExposed` asserts the metrics and the trace id header. |

## Differences from the lesson, on purpose

- The lesson's summary says "two Spring Boot applications" and its starting point says the provider is "deliberately not a Spring app". The provider is plain Java ([ADR 0001](docs/adr/0001-provider-is-not-spring.md)); the price service is the only Spring Boot application.
- The lesson's diagram looks the cache up before calling the provider ("cache -> miss -> limit"), while its expected output serves `stale: true` for a recently priced SKU when the provider is down. The cache here is the fallback after a failure, up to 60 seconds old measured from the write, so both the output and a recovered provider's fresh price are honest.
- The concurrency limit uses the `REJECT` policy rather than waiting ([ADR 0002](docs/adr/0002-reject-not-block.md)): waiting would make p99 unbounded while the provider hangs.
- A SKU with nothing remembered answers 504 when the failure was a timeout and 503 with `Retry-After` for every other failure.

## Stretch goals

| Stretch (from the lesson) | Built? |
| --- | --- |
| Replace the method-level annotations with a programmatic `RetryTemplate` and compare the policies. | No. |
| Add a circuit breaker and show the provider recovering without a flood of calls. | No. The limit and the timeouts bound the damage; ARCHITECTURE.md says where a breaker would go. |
| Build the price service as a native image and compare startup and memory under the same load. | No. |

## What is simplified for teaching

- One provider, one service instance, so the concurrency limit is per instance; a shared limit needs a gateway.
- The cache is in memory and per instance.
- Grafana runs with its default throwaway login in a container on your machine; no alerting, no retention.
- The benchmark measures a laptop, not a production host, and shares it with the load generator.

## Operability

Every provider call has a connect and a read timeout, validated at startup; retries are bounded, 503 only, with jitter; the number of calls in flight is bounded; JSON logs with trace and span ids; health with liveness and readiness probes; graceful shutdown; metrics at `/actuator/prometheus` and over OTLP; images with a pinned JRE, user 10001, a read-only root file system and no capabilities; no secrets anywhere.

## Verification status

Every row marked "pending capacity" needs Docker: restart Docker Desktop with free disk first (Docker.raw was at its 60 GiB cap on 2026-10-28), then run the command in that row.

Run on 2026-10-27 on a Mac that was very heavily loaded and whose Docker became unusable during the night (see below).

| Check | Command | Result |
| --- | --- | --- |
| Build, all tests, coverage gates (all three modules) | `./mvnw clean verify` (no Docker needed: the integration tests run the real provider in the same JVM) | passed: provider 13 tests, loadgen 8, price-service 42 unit and slice tests and 10 integration tests, "All coverage checks have been met" in each module (domain at least 95 percent, every module at least 85 percent) |
| Lint | `make lint` (`./mvnw spotless:check checkstyle:check`) | passed: Spotless clean, 0 Checkstyle violations |
| Containers: `make up`, `make run`, `make down` | pending capacity: run `make up && make run && make down` | not run: the Docker VM disk was full and read-only |
| Before/after table | pending capacity: run `make benchmark` | not run, same reason; `docs/benchmark.md` says so |
| Trace evidence | pending capacity: run `make traces` | not run, same reason; the Grafana image pull had also not completed, so the dashboard JSON and the metric names in it are unchecked against a running collector |
| Image builds, pinned base images, non-root user | pending capacity: run `make docker-build` | not run, same reason |
| Dependency audit | pending capacity: run `make audit` | not run, same reason |
| Fresh-clone test | pending capacity: `git clone . /tmp/rc && cd /tmp/rc && make lint && make test && make up && make run && make down` | not run |

## License

MIT, Sumit Srivastava, 2026. See [LICENSE](LICENSE).
