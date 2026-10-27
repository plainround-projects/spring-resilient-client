# Logging (SLF4J, Logback, structured JSON)

## What it is

A **log** is the text a program writes while it runs. **SLF4J** is the standard Java interface for writing log lines (`LOG.info("book added id={}", id)`). **Logback** is the library that actually prints them. **Structured logging** prints each line as one JSON object (`{"@timestamp":"...","level":"INFO","message":"..."}`) instead of free text, so tools can search by field. The **MDC** (mapped diagnostic context) is a per-thread map of extra fields, such as a request id, that Logback adds to every line written by that thread.

## Why this repository uses it

The lesson's operability rule is "structured logging with levels and no secrets". With JSON lines on standard output, a log collector can filter by `requestId` or `traceId`, and a person can follow one request through many lines.

## Where it appears

| Path | Role |
| --- | --- |
| `application.properties` | turns on the JSON format and adds a `service` field |
| `interfaces/web/TraceIdFilter.java` and the tracing libraries | the trace and span ids appear in each line automatically |
| `provider/.../JsonLog.java` | the plain-Java provider writes its own JSON lines |
| `src/test/resources/config/application.properties` | switches to plain text during tests, which are easier to read |
| any class with `private static final Logger LOG` | writes lines at a **level**: ERROR, WARN, INFO, DEBUG |

## How it works underneath

1. A class gets a logger with `LoggerFactory.getLogger(ThisClass.class)` and calls `LOG.info(...)`. The placeholders `{}` are filled in only if that level is enabled, which saves work.
2. Logback decides, from the level and the logger name, whether to print, and formats the line.
3. Spring Boot's `logging.structured.format.console=logstash` swaps the text formatter for one that prints a JSON object with fields such as `@timestamp`, `message`, `logger_name`, `level`, and every MDC entry.
4. The MDC lives in the thread. A filter puts the id before the request runs and removes it afterwards; every `LOG` call during the request carries it. Thread pools reuse threads, so the removal in `finally` is essential.
5. Stack traces of exceptions go in the `stack_trace` field of the same JSON object, so one event is one line.

## Walkthrough of this repository's use

The setting:

```properties
logging.structured.format.console=logstash
```

`format.console=logstash` selects the Logstash JSON layout for the console.

Trace and span ids come from the tracing library (see `micrometer-opentelemetry.md`): when a request is being traced, Micrometer Tracing puts `traceId` and `spanId` in the MDC, so every JSON line of the request carries them and can be matched to the trace.

A logger call in the service:

```java
LOG.warn("provider failed sku={} reason={}", sku, failure.getClass().getSimpleName());
```

`warn` is the level for "something went wrong but we handled it". The values go in `{}` placeholders, never glued into the text with `+`.

The provider has no logging library at all:

```java
private static void write(String level, String message) {
    System.out.println(
            "{\"@timestamp\":\""
                    + Instant.now()
                    + "\",\"level\":\""
                    + level
                    + "\",\"service\":\"provider\""
                    + ",\"message\":\""
                    + escape(message)
                    + "\"}");
}
```

It prints one JSON object per line with `System.out.println`. Quotes and newlines in the message are escaped by `escape`, so the output stays valid JSON.

No secrets are logged: nothing in the code prints passwords, tokens or request bodies.

## Try it yourself

1. Start the service and send a request: `curl localhost:18104/price/sku-1`. Then read the service log: `docker compose logs --tail 20` (or the terminal of `make serve`).
2. Pretty-print the last line: `docker compose logs --no-log-prefix --tail 1 | jq .`.
3. Turn the JSON off for one run: `--logging.structured.format.console=` (an empty value) and compare.
4. Run `./mvnw test -Dtest=RequestIdFilterTest` (if present in the project) to see the unsafe-id test.

## Mistakes people make

- Logging with string concatenation: `LOG.info("id=" + id)` builds the text even when INFO is off.
- Logging a whole request body: it may hold personal data.
- Leaving values in the MDC: the next request on the same thread inherits them.
- Printing with `System.out`: no level, no timestamp, no fields. A Checkstyle rule forbids it in this repository (except the provider's logger, which is documented).

## Check yourself

1. What does structured logging change? *Each line is a JSON object with fields.*
2. What is the MDC? *A per-thread map whose entries are added to every log line.*
3. Why remove the id in `finally`? *Threads are reused by later requests.*
4. Why validate the incoming request id? *To stop log forging.*
5. Which level fits "handled problem"? *WARN.*

## Further reading

- Spring Boot 4.1.2, "Structured Logging": https://docs.spring.io/spring-boot/4.1.2/reference/features/logging.html#features.logging.structured
- SLF4J manual: https://www.slf4j.org/manual.html
- Logback manual (MDC): https://logback.qos.ch/manual/mdc.html
