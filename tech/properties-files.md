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

First, three words this note uses:

- An **environment variable** is a named value that the operating system gives to every program it starts, for example `POSTGRES_PASSWORD=abc`. A program reads it by name. Containers and scripts use them to pass settings in without changing any file.
- A **port** is a number (0 to 65535) that tells the operating system which program on this machine should receive a network connection. A web server listens on one port; a client says "host plus port" to reach it.
- The **classpath** is the list of places where Java looks for classes and resource files while the program runs. Maven copies everything under `src/main/resources` into `target/classes`, which is on the classpath.

How the file is used:

1. At startup Spring Boot collects settings from several **sources** and ranks them. From strongest to weakest, the ones that matter here: command-line arguments (`--server.port=0`), then environment variables, then the properties files.
2. **Relaxed binding:** the environment variable `READING_LIST_MAX_PAGE_SIZE` and the property `reading-list.max-page-size` name the same setting. Upper case with underscores works for any key. That is how a container is configured without touching the file.
3. **Placeholders:** `${NAME:default}` means "the value of NAME (an environment variable or another setting) if it is set, otherwise `default`". `${NAME}` with no default stays unresolved if `NAME` is missing.
4. Two files with the same name can both apply. Spring Boot reads `classpath:/application.properties` and `classpath:/config/application.properties`, and the second wins. That is why the test overrides live in a `config/` folder: a test file placed beside the main one would **replace** it (the same classpath path), and the application would lose its settings.
5. A line that starts with `#` is a comment. Everything after the first `=` is the value, including spaces.

## Walkthrough of this repository's use

The whole main file:

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

Every key in that file, with the value it has here and what it does:

| Key | Value in this file | What it does |
| --- | --- | --- |
| `spring.application.name` | `price-service` | The name of the application, used in logs, metrics and traces. |
| `price.provider.base-url` | `${PRICE_PROVIDER_BASE_URL:http://localhost:18103}` | Where the provider is. Read from the environment variable `PRICE_PROVIDER_BASE_URL`, with a default for running on your own machine. |
| `price.provider.connect-timeout` | `1s` | The longest opening a connection to the provider may take. |
| `price.provider.read-timeout` | `2s` | The longest the service waits for the provider's answer once connected. |
| `price.protection` | `${PRICE_PROTECTION:full}` | `full` builds the protected service (timeouts, retry, limit, cache). `none` builds the unprotected version used as the benchmark's "before". The value comes from the environment variable `PRICE_PROTECTION` if set. |
| `price.provider.retry.max-retries` | `2` | How many times a failed call is repeated. Two retries means at most three calls in all. |
| `price.provider.retry.delay-ms` | `100` | The wait, in milliseconds, before the first retry. |
| `price.provider.retry.multiplier` | `2.0` | The delay is multiplied by this number after each retry (2.0 doubles it). |
| `price.provider.retry.jitter-ms` | `50` | Up to this many random milliseconds are added to each retry delay. |
| `price.provider.retry.max-delay-ms` | `1000` | The longest any single retry delay may grow to, in milliseconds. |
| `price.provider.max-concurrent-calls` | `${PRICE_PROVIDER_MAX_CONCURRENT_CALLS:10}` | The most provider calls allowed in flight at the same moment; further calls are refused at once. Read from `PRICE_PROVIDER_MAX_CONCURRENT_CALLS` if set. |
| `price.cache.ttl` | `60s` | How long after the provider gave a quote it may still be served as a stale answer. |
| `price.cache.max-entries` | `10000` | The most SKUs the quote cache remembers; when it is full, the oldest entry is dropped. |
| `spring.threads.virtual.enabled` | `true` | Run each web request on a virtual thread (a very cheap thread managed by the JVM). |
| `spring.mvc.problemdetails.enabled` | `true` | Turn on `application/problem+json` answers for the framework's own errors. |
| `logging.structured.format.console` | `logstash` | Print each log line to the console as one JSON object (the "logstash" layout) instead of plain text. Tools can then search by field. |
| `management.endpoints.web.exposure.include` | `health,prometheus` | Which Actuator endpoints are reachable over HTTP. An endpoint is a built-in address under `/actuator`; by default only `health` is reachable, and this list adds the others. |
| `management.endpoint.health.probes.enabled` | `true` | Turn on two extra health addresses, `/actuator/health/liveness` ("the process is alive") and `/actuator/health/readiness` ("it can take requests now"). |
| `server.shutdown` | `graceful` | `graceful` means: when told to stop, stop accepting new requests, let the running ones finish, then exit. |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | The longest the graceful shutdown waits for running requests. |
| `management.logging.export.otlp.enabled` | `false` | Do not send log lines to an OpenTelemetry collector (logs stay on standard output only). |
| `management.tracing.sampling.probability` | `1.0` | The share of requests whose trace is kept. `1.0` keeps every one; production systems usually keep a small share. |
| `management.opentelemetry.tracing.export.otlp.endpoint` | `${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:14318/v1/traces}` | The HTTP address where finished trace spans are sent (the OpenTelemetry protocol, OTLP). The text after `:` inside `${...}` is the default when the environment variable is not set. |
| `management.otlp.metrics.export.url` | `${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:http://localhost:14318/v1/metrics}` | The HTTP address where metrics are pushed. |
| `management.otlp.metrics.export.step` | `10s` | How often metrics are pushed to the collector. |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | For every incoming HTTP request, record how long it took in a histogram (counts per duration bucket), so a backend can compute the 50th and 99th percentile. |

The comment lines in the file explain the reason for each group of settings; they are part of the file and worth reading in place.

The test file, which Spring Boot merges over the main one when tests run:

```properties
# Test-only overrides, merged over src/main/resources/application.properties.
logging.structured.format.console=
# Spans and metrics are created (the tests read the trace id and the Prometheus text) but not sent anywhere.
management.tracing.export.enabled=false
management.otlp.metrics.export.enabled=false
management.logging.export.otlp.enabled=false
management.endpoints.web.exposure.include=health,prometheus
# Short timeouts and delays keep the failure tests fast; the shape of every rule is unchanged.
price.provider.connect-timeout=500ms
price.provider.read-timeout=500ms
price.provider.retry.delay-ms=20
price.provider.retry.jitter-ms=5
price.provider.max-concurrent-calls=3
```

| Key | Value in this file | What it does |
| --- | --- | --- |
| `logging.structured.format.console` | `(empty)` | Print each log line to the console as one JSON object (the "logstash" layout) instead of plain text. Tools can then search by field. |
| `management.tracing.export.enabled` | `false` | Switch the export of traces off (used in tests; spans are still created, so trace ids still exist). |
| `management.otlp.metrics.export.enabled` | `false` | Switch the sending of metrics over OTLP off (used in tests so nothing tries to reach a collector). |
| `management.logging.export.otlp.enabled` | `false` | Do not send log lines to an OpenTelemetry collector (logs stay on standard output only). |
| `management.endpoints.web.exposure.include` | `health,prometheus` | Which Actuator endpoints are reachable over HTTP. An endpoint is a built-in address under `/actuator`; by default only `health` is reachable, and this list adds the others. |
| `price.provider.connect-timeout` | `500ms` | The longest opening a connection to the provider may take. |
| `price.provider.read-timeout` | `500ms` | The longest the service waits for the provider's answer once connected. |
| `price.provider.retry.delay-ms` | `20` | The wait, in milliseconds, before the first retry. |
| `price.provider.retry.jitter-ms` | `5` | Up to this many random milliseconds are added to each retry delay. |
| `price.provider.max-concurrent-calls` | `3` | The most provider calls allowed in flight at the same moment; further calls are refused at once. Read from `PRICE_PROVIDER_MAX_CONCURRENT_CALLS` if set. |

One more file has a similar look but is not a properties file for Spring: `.mvn/jvm.config`. It holds one JVM option per line, and Maven passes them to the Java process that runs Maven itself (see `maven.md`).

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
