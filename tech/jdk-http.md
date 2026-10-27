# The JDK's HTTP server and HTTP client (the provider and the load generator)

## What it is

Java ships a small HTTP server (`com.sun.net.httpserver.HttpServer`, in the `jdk.httpserver` module) and an HTTP client (`java.net.http.HttpClient`). They need no framework and no library. Two programs here use them: the **fake provider** (a server you can switch between healthy, slow, failing and silent) and the **load generator** (a client that sends requests at a steady rate and measures the answers).

## Why this repository uses it

The lesson says the fake provider is "deliberately not a Spring app": it must be an independent witness. Its own counters of calls received and calls in flight are checked against what the client claims. A plain program with no framework cannot share bugs with the service it tests. The load generator is plain Java for the same reason, and because it must be run from a jar with one command.

## Where it appears

| Path | Role |
| --- | --- |
| `provider/src/main/java/dev/plainround/provider/FakeProvider.java` | the server, its modes and counters |
| `provider/.../ProviderMode.java`, `ProviderMain.java`, `JsonLog.java` | the mode names, the entry point, the JSON log lines |
| `loadgen/src/main/java/dev/plainround/loadgen/LoadRunner.java`, `LoadMain.java`, `Report.java` | the load generator and its report |
| `provider/src/test/...`, `loadgen/src/test/...` | tests of both |

## How it works underneath

1. `HttpServer.create(new InetSocketAddress(port), 0)` opens a listening socket. `createContext("/path", handler)` registers a handler for a URL prefix. `setExecutor(...)` chooses which threads run handlers (here a new virtual thread per request). `start()` begins accepting requests.
2. A handler receives an `HttpExchange`: it reads the request, then calls `sendResponseHeaders(status, length)`, writes the body and closes the exchange.
3. The provider keeps state in `AtomicInteger`s (safe to change from many threads without a lock): `callsReceived`, `inFlight`, `maxInFlight`.
4. The client `HttpClient.send(request, handler)` blocks until the answer; `request.timeout(...)` bounds the wait.
5. The load generator is **open loop**: it starts a request every `1/rate` seconds whether or not earlier ones have finished. A closed loop (send the next after the last returns) would quietly lower the load whenever the target slows down, hiding the problem it is meant to show.

## Walkthrough of this repository's use

**The provider's modes and counters.**

```java
private void quote(HttpExchange exchange) throws IOException {
    callsReceived.incrementAndGet();
    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
    try {
        if (failNext.getAndUpdate(remaining -> Math.max(remaining - 1, 0)) > 0) {
            reply(exchange, 503, "text/plain", "unavailable");
            return;
        }
        switch (mode) {
            case FAIL503 -> reply(exchange, 503, "text/plain", "unavailable");
            case HANG -> {
                sleep(hangMillis.get());
                reply(exchange, 200, "application/json", "{}");
            }
            case SLOW -> {
                sleep(slowMillis.get());
                reply(exchange, 200, "application/json", quoteJson(exchange.getRequestURI()));
            }
            default ->
                    reply(
                            exchange,
                            200,
                            "application/json",
                            quoteJson(exchange.getRequestURI()));
        }
    } finally {
        inFlight.decrementAndGet();
    }
}
```

`callsReceived.incrementAndGet()` counts the call as soon as it arrives. `maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max)` raises the recorded maximum to the current in-flight count if it is higher. `failNext.getAndUpdate(...)` implements "answer 503 for the next N calls". The `switch (mode)` then answers according to the mode: `FAIL503` replies 503 at once; `HANG` sleeps (10 seconds by default) then replies `{}`; `SLOW` sleeps `slowMillis` (5 seconds by default) then replies a price; anything else replies the price. The `finally` always decrements `inFlight`.

**Switching the mode at run time.**

```java
private void setMode(HttpExchange exchange) throws IOException {
    if (!"PUT".equals(exchange.getRequestMethod())) {
        reply(exchange, 405, "text/plain", "use PUT");
        return;
    }
    try {
        String query = exchange.getRequestURI().getQuery();
        mode = ProviderMode.parse(queryValue(query, "mode"));
        String delay = queryValue(query, "delayMs");
        if (delay != null) {
            slowMillis.set(Long.parseLong(delay));
        }
        String failures = queryValue(query, "failNext");
        failNext.set(failures == null ? 0 : Integer.parseInt(failures));
        JsonLog.info("mode set to " + mode + ", slow delay " + slowMillis.get() + " ms");
        reply(exchange, 200, "application/json", statsJson());
    } catch (IllegalArgumentException exception) {
        reply(exchange, 400, "text/plain", exception.getMessage());
    }
}
```

`PUT /admin/mode?mode=slow&delayMs=5000` sets the mode and the delay; `failNext=2` fails the next two calls. A wrong mode name is a 400.

**The deterministic price.**

```java
private static String quoteJson(URI uri) {
    String path = uri.getPath();
    String sku = path.substring(path.lastIndexOf('/') + 1);
    BigDecimal price =
            BigDecimal.valueOf(1000 + Math.floorMod(sku.hashCode(), 9000))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
    return "{\"price\":" + price.toPlainString() + "}";
}
```

The price comes from the SKU's hash code, so the same SKU always gets the same price and tests can compare.

**The load generator.**

```java
public Report run(
        String name, IntFunction<URI> urlForRequest, int requestsPerSecond, int seconds)
        throws InterruptedException {
    ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    AtomicInteger errors = new AtomicInteger();
    AtomicInteger stale = new AtomicInteger();
    int total = requestsPerSecond * seconds;
    long startNanos = System.nanoTime();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int number = 0; number < total; number++) {
            sleepUntil(startNanos + number * (1_000_000_000L / requestsPerSecond));
            URI uri = urlForRequest.apply(number);
            pool.submit(() -> send(uri, latencies, errors, stale));
        }
        pool.shutdown();
        pool.awaitTermination(REQUEST_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
    }
    return Report.of(name, toArray(latencies), errors.get(), stale.get());
}
```

`startNanos + number * (1_000_000_000L / requestsPerSecond)` is the planned time of request number `number`; `sleepUntil` waits for it. Each request is `pool.submit(() -> send(...))` on a virtual thread, so a slow answer never delays the next send. After the last send the pool is shut down and given up to 20 seconds to finish.

```java
private static double percentile(long[] sorted, int percent) {
    if (sorted.length == 0) {
        return 0;
    }
    int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
    return sorted[Math.max(rank, 1) - 1] / 1_000_000.0;
}
```

The percentile uses the **nearest rank**: sort the latencies, take element number `ceil(p/100 × n)`.

**Every class of the provider and of the load generator:**

| File | What it is |
| --- | --- |
| `provider/src/main/java/dev/plainround/provider/FakeProvider.java` | A quote provider you control. |
| `provider/src/main/java/dev/plainround/provider/JsonLog.java` | One JSON object per line on standard output: level, message and time, nothing else. |
| `provider/src/main/java/dev/plainround/provider/ProviderMain.java` | Starts the fake provider on `PORT` (default 8081) and stops it when the process is told to. |
| `provider/src/main/java/dev/plainround/provider/ProviderMode.java` | How the fake provider answers a quote request. |

| File | What it is |
| --- | --- |
| `loadgen/src/main/java/dev/plainround/loadgen/LoadMain.java` | Command line: `java -jar loadgen.jar --target http://host/price/{sku` --skus 10 --rps 100 --seconds 30 [--side http://host/skus/sku-1 --side-rps 10]}. |
| `loadgen/src/main/java/dev/plainround/loadgen/LoadRunner.java` | Sends requests at a fixed rate whether or not the earlier ones have come back (an open loop), so a slow target shows up as latency instead of quietly lowering the load. |
| `loadgen/src/main/java/dev/plainround/loadgen/Report.java` | The outcome of one load run against one target. |

**Their tests:**

- `provider/src/test/java/dev/plainround/provider/FakeProviderTest.java`:
  - `normalModeAnswers200WithADeterministicPriceAndCountsTheCall`
  - `fail503ModeAnswers503AtOnce`
  - `failNextAnswers503ForExactlyThatManyCallsThenReturnsToTheMode`
  - `slowModeAnswersAfterTheConfiguredDelay`
  - `hangModeIsSilentLongerThanAClientWillWaitButStillCountsTheCall`
  - `theAdminEndpointSwitchesTheModeAtRunTime`
  - `anUnknownModeIs400AndAGetOnTheAdminEndpointIs405`
  - `maxInFlightRecordsTheMostCallsServedAtOnce`
  - `resetZeroesTheCountersAndMetricsExposeThem`
  - `modeNamesParseIgnoringCaseAndRejectNonsense`
  - `theMainEntryPointStartsAProviderThatAnswersHealth`
  - `hangLastsAsLongAsConfiguredAndThenAnswersEmptyJson`
  - `jsonLogEscapesQuotesAndNewlines`

- `loadgen/src/test/java/dev/plainround/loadgen/LoadgenTest.java`:
  - `percentilesUseTheNearestRank`
  - `anEmptyRunReportsZeros`
  - `theJsonLineCarriesEveryNumber`
  - `optionsParseInPairs`
  - `theRunnerHoldsTheRateAndCountsErrorsAndStaleAnswers`
  - `mainRunsThePriceTargetAndTheSideTargetAndPrintsOneJsonLineEach`
  - `anUnreachableTargetCountsAsErrors`
  - `aMissingTargetIsRejected`

## Try it yourself

1. Run the provider: `PORT=18105 java -jar provider/target/provider-0.1.0.jar` (after `./mvnw -DskipTests package`).
2. `curl -s localhost:18105/quotes/sku-1` prints `{"price":...}`; `curl -s localhost:18105/admin/stats` prints the counters.
3. `curl -X PUT 'localhost:18105/admin/mode?mode=fail503'; curl -si localhost:18105/quotes/sku-1` shows a 503.
4. Run the load generator against it: `java -jar loadgen/target/loadgen-0.1.0.jar --target 'http://localhost:18105/quotes/{sku}' --skus 3 --rps 20 --seconds 3` prints one JSON line with `p50Ms`, `p99Ms` and `errorRate`.

## Mistakes people make

- Using the system under test's own counters as proof.
- A closed-loop load generator that backs off when the target slows down.
- Reading the average instead of p99.
- Forgetting the handler must close the exchange.

## Check yourself

1. Why is the provider not a Spring application? *To be an independent witness with its own counters.*
2. What does `maxInFlight` record? *The most calls being served at the same moment.*
3. What is an open-loop load? *Requests are sent on schedule, whether or not earlier ones returned.*
4. How is the nearest-rank percentile found? *Sort, then take element `ceil(p/100 × n)`.*
5. Why is the price derived from the SKU? *So repeated calls give the same price.*

## Further reading

- `com.sun.net.httpserver` (module `jdk.httpserver`): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- `java.net.http` (HTTP Client): https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
