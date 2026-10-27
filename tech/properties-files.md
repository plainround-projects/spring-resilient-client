# Properties files (application.properties)

## What it is

A properties file is a plain text file of `key=value` lines. A line that starts with `#` is a comment. Spring Boot reads a file named `application.properties` from the classpath (the places Java looks for code and resources; here `src/main/resources`) and turns each line into a setting.

## Why this repository uses it

The lesson wants configuration to come from the environment with checked defaults, never hard-coded in code. This file holds every setting that is not code: ports, timeouts, limits, logging style, shutdown behavior.

## Where it appears

| Path | What it does |
| --- | --- |
| `price-service/src/main/resources/application.properties` | the price service's settings |
| `price-service/src/test/resources/config/application.properties` | test-only changes: short timeouts, no export of metrics and traces |
| `.mvn/jvm.config` | a different thing with a similar look: one JVM option per line |

## How it works underneath

1. At startup Spring Boot collects settings from several **sources** and ranks them. From strongest to weakest, the ones that matter here: command-line arguments (`--server.port=0`), operating-system environment variables, then the properties files.
2. **Relaxed binding:** the environment variable `READING_LIST_MAX_PAGE_SIZE` and the property `reading-list.max-page-size` name the same setting. Upper case with underscores works for any key. That is how a container is configured without touching the file.
3. **Placeholders:** `${NAME:default}` means "the value of NAME if it is set, otherwise `default`". `${NAME}` with no default stays unresolved if `NAME` is missing.
4. Two files with the same name can both apply. Spring Boot reads `classpath:/application.properties` and `classpath:/config/application.properties` and the second wins. That is why the test overrides live in a `config/` folder: a test file placed beside the main one would **replace** it (the same classpath path), and the app would lose its settings.

## Walkthrough of this repository's use

The whole file:

```properties
spring.application.name=price-service

# Where the provider is. Timeouts apply to every call; neither can be zero or negative (validated at startup).
price.provider.base-url=${PRICE_PROVIDER_BASE_URL:http://localhost:18103}
price.provider.connect-timeout=1s
price.provider.read-timeout=2s

# Protection: "full" is the service this project builds; "none" is the benchmark's "before" (no timeouts, no retry,
# no concurrency limit, no cache). Never run "none" in production.
price.protection=${PRICE_PROTECTION:full}

# Retry only a 503, at most twice, delays 100 ms then 200 ms (x2), each with up to 50 ms of random jitter.
price.provider.retry.max-retries=2
price.provider.retry.delay-ms=100
price.provider.retry.multiplier=2.0
price.provider.retry.jitter-ms=50
price.provider.retry.max-delay-ms=1000

# At most this many provider calls in flight; the rest are refused at once (policy REJECT), sized to the provider.
price.provider.max-concurrent-calls=${PRICE_PROVIDER_MAX_CONCURRENT_CALLS:10}

# The last good quote is served, labeled stale, for up to this long after the provider gave it.
price.cache.ttl=60s
price.cache.max-entries=10000

# Many blocking calls on virtual threads: a slow provider no longer ties up platform threads.
spring.threads.virtual.enabled=true
spring.mvc.problemdetails.enabled=true

# Operability: JSON logs with trace ids, probes, graceful shutdown.
logging.structured.format.console=logstash
management.endpoints.web.exposure.include=health,prometheus
management.endpoint.health.probes.enabled=true
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s

# Metrics and traces to an OTLP endpoint (the Grafana stack in docker-compose.yml). A missing collector
# never stops the service; exports are retried by the SDK.
management.logging.export.otlp.enabled=false
management.tracing.sampling.probability=1.0
management.opentelemetry.tracing.export.otlp.endpoint=${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:14318/v1/traces}
management.otlp.metrics.export.url=${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:http://localhost:14318/v1/metrics}
management.otlp.metrics.export.step=10s
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

- `price.provider.base-url=${PRICE_PROVIDER_BASE_URL:http://localhost:18103}` is where the provider is; the compose file overrides it with an environment variable.
- `price.provider.connect-timeout=1s` and `read-timeout=2s` are the two timeouts on every provider call (see `rest-client.md`). They are read into `ProviderProperties`, which refuses zero or negative values at startup.
- `price.protection=${PRICE_PROTECTION:full}` switches the service between the protected version and the unprotected "before" version used by the benchmark.
- `price.provider.retry.*` and `price.provider.max-concurrent-calls` feed the annotations `@Retryable` and `@ConcurrencyLimit` (see `spring-resilience.md`).
- `price.cache.ttl=60s` and `max-entries` configure the stale-quote cache.
- `spring.threads.virtual.enabled=true` runs requests on virtual threads (see `virtual-threads.md`).
- `management.tracing.*`, `management.opentelemetry.*` and `management.otlp.*` configure where traces and metrics are sent (see `micrometer-opentelemetry.md`).

## Try it yourself

1. Start the application with a different port without editing the file: `SERVER_PORT=18999 ./mvnw spring-boot:run -pl price-service` and look for `Tomcat started on port 18999` in the log.
2. Override with an argument: add `-Dspring-boot.run.arguments=--server.port=18998`.
3. Break a validated value and watch startup fail: `./mvnw -pl price-service spring-boot:run -Dspring-boot.run.arguments=--price.provider.read-timeout=0s` stops with a message that names the timeout rule.
4. `grep -v '^#' src/main/resources/application.properties` (from the module folder) shows the settings without the comments.

## Mistakes people make

- Putting test settings in `src/test/resources/application.properties`: it hides the main file. Use `config/application.properties`.
- Writing spaces around `=`: they become part of the value in some tools; this repository writes none.
- Expecting a changed file to apply without a rebuild: the file is copied to `target/classes` at build time.
- Putting a secret in the file: here, secrets come from environment variables only.

## Check yourself

1. What does `${A:b}` mean? *The value of `A`, or `b` if `A` is not set.*
2. Which wins, an environment variable or the file? *The environment variable.*
3. Why is the test file in a `config/` folder? *So it adds to the main file instead of replacing it.*
4. How can `READING_LIST_MAX_PAGE_SIZE` set `reading-list.max-page-size`? *Relaxed binding maps the two names.*
5. What happens to a `#` line? *It is a comment and ignored.*

## Further reading

- Spring Boot 4.1.2, "Externalized Configuration": https://docs.spring.io/spring-boot/4.1.2/reference/features/external-config.html
- Common application properties: https://docs.spring.io/spring-boot/4.1.2/appendix/application-properties/index.html
