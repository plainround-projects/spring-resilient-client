# Spring Boot Actuator (health, probes, metrics endpoints)

## What it is

Actuator is a Spring Boot module that adds operational endpoints to a running application: `/actuator/health` (is it healthy?), `/actuator/beans` (what objects exist?), `/actuator/metrics` and, with a Prometheus library, `/actuator/prometheus` (numbers about the running service). You choose which ones are reachable.

## Why this repository uses it

The lesson's first check reads `/actuator/beans`; the lesson's last step needs metrics, and a container needs a health endpoint so Docker can tell when the service is ready. **Liveness** answers "is the process alive?"; **readiness** answers "can it take traffic now?". Orchestrators use them to decide whether to restart a container or send it requests.

## Where it appears

| Path | Role |
| --- | --- |
| `application.properties` | which endpoints are exposed, probes on |
| `docker-compose.yml` | the container health check calls `/actuator/health/readiness` |
| `Makefile` (`make health`) | calls the readiness endpoint with curl and jq |
| `PriceServiceBehaviorIT.java` | asserts the Prometheus text contains the service's own metrics |

## How it works underneath

1. Each endpoint is a bean that Actuator registers on a URL under `/actuator`.
2. By default only `health` is exposed over HTTP. `management.endpoints.web.exposure.include` lists the others to expose.
3. `health` combines **health indicators**: small checks such as "disk space is fine". The overall status is `UP` only if every indicator is `UP`.
4. With `management.endpoint.health.probes.enabled=true`, two extra groups appear: `/actuator/health/liveness` and `/actuator/health/readiness`. During graceful shutdown readiness turns `OUT_OF_SERVICE` first so traffic stops before the process ends.
5. Details such as which indicator failed are hidden unless you ask (`show-details`), so outsiders learn nothing about the internals.

## Walkthrough of this repository's use

```properties
management.endpoints.web.exposure.include=health,prometheus
management.endpoint.health.probes.enabled=true
```

`health` and `prometheus` are exposed. `/actuator/prometheus` prints every metric in the Prometheus text format (see `micrometer-opentelemetry.md`).

The compose health check uses plain bash because the Java image has no `curl`:

```yaml
test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8081 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
```

It opens a TCP connection to the application's own port, sends an HTTP request line by hand, and passes if the answer contains `"status":"UP"` (see `bash.md` and `docker-compose.md`).

## Try it yourself

1. `curl -s localhost:18104/actuator/health | jq .` shows `{"groups":["liveness","readiness"],"status":"UP"}`.
2. `curl -s .../actuator/health/readiness | jq .` shows `{"status":"UP"}`.
3. `curl -s localhost:18104/actuator/prometheus | grep price_provider_attempts` shows the retry counters.
4. Stop the service with Ctrl+C and watch the log: readiness goes down, then the process ends.

## Mistakes people make

- Exposing every endpoint on a public port: `beans`, `env` and `heapdump` reveal internals.
- Using liveness to mean "the database is up": a database outage should make the service not ready, not make an orchestrator restart it in a loop.
- Forgetting that exposure is a separate switch from "enabled".

## Check yourself

1. What is the difference between liveness and readiness? *Alive versus able to take traffic.*
2. Which endpoints are exposed by default over HTTP? *Only `health`.*
3. Why does the container image expose only `health`? *`beans` reveals internals.*
4. What makes overall health `DOWN`? *Any indicator that is down.*
5. Why does the health check use bash instead of curl? *The image has no curl.*

## Further reading

- Spring Boot 4.1.2, "Actuator": https://docs.spring.io/spring-boot/4.1.2/reference/actuator/index.html
- Kubernetes probes in Boot: https://docs.spring.io/spring-boot/4.1.2/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes
