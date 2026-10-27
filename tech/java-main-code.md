# The application code, class by class

## What it is

This note is a tour of the Java code that makes up the running program (the code in `src/main/java`; the tests are in [the test code note](java-test-code.md)). Every class is printed in full, exactly as it is in the repository, and under each one is a reading guide that explains each field, constructor and method in plain words.

If you have never seen Java, read [Java](java.md) first. It explains packages, classes, methods, imports and types. This note then shows all of them in use.

## Why this repository uses it

The code is the product. The repository is small on purpose so that you can read all of it. Reading every class, in the order given below, shows how a web request turns into a result.

## Where it appears

There are three Maven modules, each with its own `src/main/java`:

| Module | Job |
| --- | --- |
| `provider` | a small fake price provider that answers HTTP requests and can be told to fail or hang |
| `loadgen` | a program that sends requests at a steady rate and counts the outcomes |
| `price-service` | the real application: it calls the provider with timeouts, retries and a concurrency limit |

## How it works underneath

1. When the program starts, Java runs the `main` method of the application class. That method calls `SpringApplication.run`, which scans the package and its sub-packages for classes carrying Spring annotations and creates one object of each.
2. Objects ask for the other objects they need in their constructors. Spring finds a matching object and passes it in. This is called constructor injection.
3. When a request arrives, Tomcat (the web server inside the program) hands it to Spring, which picks the controller method by URL and HTTP verb, converts JSON into Java objects, validates them, and calls the method.
4. The return value is converted back to JSON and written as the response. A thrown exception is turned into an error response by the exception handler class.

## Walkthrough of this repository's use

Each class below is printed whole. Under it, a list explains each part. The "What the body does" lines are a mechanical translation of the statements, in order, into words; they quote the code in backticks so you can match each line with the listing.

### `provider/src/main/java/dev/plainround/provider/FakeProvider.java`

Package `dev.plainround.provider`. A quote provider you control.

The whole file, as it is in the repository:

```java
package dev.plainround.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A quote provider you control. It is deliberately not a Spring application: a plain {@link
 * HttpServer} with a mode you flip at run time and its own counters (calls received, calls in
 * flight, the most in flight at once), so every claim about retries and concurrency can be checked
 * against a count the client did not make.
 *
 * <ul>
 *   <li>{@code GET /quotes/{sku}} answers according to the current {@link ProviderMode}.
 *   <li>{@code PUT /admin/mode?mode=slow&delayMs=5000} switches the mode; {@code &failNext=2} makes
 *       the next two calls answer 503 whatever the mode is.
 *   <li>{@code GET /admin/stats} returns the counters as JSON; {@code POST /admin/reset} zeroes
 *       them.
 *   <li>{@code GET /metrics} returns the counters in the Prometheus text format; {@code GET
 *       /health} answers ok.
 * </ul>
 */
public final class FakeProvider implements AutoCloseable {

    /**
     * How long {@link ProviderMode#HANG} stays silent unless {@link #hangMillis(long)} says
     * otherwise.
     */
    public static final long HANG_MILLIS = 10_000;

    private static final long DEFAULT_SLOW_MILLIS = 5_000;

    private final AtomicInteger callsReceived = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicLong slowMillis = new AtomicLong(DEFAULT_SLOW_MILLIS);
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicLong hangMillis = new AtomicLong(HANG_MILLIS);
    private volatile ProviderMode mode = ProviderMode.NORMAL;
    private final HttpServer server;

    /**
     * Creates the provider; call {@link #start()} to accept requests.
     *
     * @param port the port to listen on, 0 for any free port
     * @throws IOException if the port cannot be bound
     */
    public FakeProvider(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/quotes/", this::quote);
        server.createContext("/admin/mode", this::setMode);
        server.createContext(
                "/admin/stats", exchange -> reply(exchange, 200, "application/json", statsJson()));
        server.createContext("/admin/reset", this::reset);
        server.createContext(
                "/metrics",
                exchange -> reply(exchange, 200, "text/plain; version=0.0.4", metricsText()));
        server.createContext("/health", exchange -> reply(exchange, 200, "text/plain", "ok"));
    }

    /** Starts accepting requests. */
    public void start() {
        server.start();
    }

    /**
     * Returns the port actually bound.
     *
     * @return the port
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Switches the mode directly, for tests that run the provider in the same JVM.
     *
     * @param newMode the mode to answer with from now on
     */
    public void mode(ProviderMode newMode) {
        mode = newMode;
    }

    /**
     * Sets how long {@link ProviderMode#HANG} stays silent, so tests do not leave ten-second calls
     * behind.
     *
     * @param millis the silence in milliseconds
     */
    public void hangMillis(long millis) {
        hangMillis.set(millis);
    }

    /**
     * Makes the next calls answer 503 whatever the mode is, then return to the mode. This is how a
     * request that fails twice and succeeds on the third attempt is produced.
     *
     * @param calls how many calls to fail
     */
    public void failNext(int calls) {
        failNext.set(calls);
    }

    /**
     * Sets how long {@link ProviderMode#SLOW} waits before answering.
     *
     * @param millis the delay in milliseconds
     */
    public void slowMillis(long millis) {
        slowMillis.set(millis);
    }

    /**
     * Returns how many quote requests have arrived since the last reset.
     *
     * @return the count
     */
    public int callsReceived() {
        return callsReceived.get();
    }

    /**
     * Returns how many quote requests are being served right now.
     *
     * @return the count
     */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * Returns the most quote requests that were ever being served at the same moment since the last
     * reset.
     *
     * @return the maximum
     */
    public int maxInFlight() {
        return maxInFlight.get();
    }

    /** Zeroes the counters. */
    public void resetCounters() {
        callsReceived.set(0);
        maxInFlight.set(inFlight.get());
    }

    @Override
    public void close() {
        server.stop(1);
    }

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

    private void reset(HttpExchange exchange) throws IOException {
        resetCounters();
        reply(exchange, 200, "application/json", statsJson());
    }

    private String statsJson() {
        return "{\"mode\":\""
                + mode.name().toLowerCase(java.util.Locale.ROOT)
                + "\",\"slowDelayMs\":"
                + slowMillis.get()
                + ",\"callsReceived\":"
                + callsReceived.get()
                + ",\"inFlight\":"
                + inFlight.get()
                + ",\"maxInFlight\":"
                + maxInFlight.get()
                + "}";
    }

    private String metricsText() {
        return "# TYPE provider_calls_received_total counter\nprovider_calls_received_total "
                + callsReceived.get()
                + "\n# TYPE provider_in_flight gauge\nprovider_in_flight "
                + inFlight.get()
                + "\n# TYPE provider_in_flight_max gauge\nprovider_in_flight_max "
                + maxInFlight.get()
                + "\n";
    }

    private static String quoteJson(URI uri) {
        String path = uri.getPath();
        String sku = path.substring(path.lastIndexOf('/') + 1);
        BigDecimal price =
                BigDecimal.valueOf(1000 + Math.floorMod(sku.hashCode(), 9000))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
        return "{\"price\":" + price.toPlainString() + "}";
    }

    private static String queryValue(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name) && parts.length == 2) {
                return parts[1];
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException clientGone) {
            // The client gave up (its read timeout fired) before this answer; there is nobody to
            // tell.
            JsonLog.info(
                    "client closed the connection before the answer: " + clientGone.getMessage());
        } finally {
            exchange.close();
        }
    }
}
```

Reading it, one piece at a time:

- **`FakeProvider`**, a class that implements `AutoCloseable`. A quote provider you control. It is deliberately not a Spring application: a plain {@link HttpServer} with a mode you flip at run time and its own counters (calls received, calls in flight, the most in flight at once), so every claim about retries and concurrency can be checked against a count the client did not make. `GET /quotes/{sku`} answers according to the current `ProviderMode`. `PUT /admin/mode?mode=slow&delayMs=5000` switches the mode; `&failNext=2` makes the next two calls answer 503 whatever the mode is. `GET /admin/stats` returns the counters as JSON; `POST /admin/reset` zeroes them. `GET /metrics` returns the counters in the Prometheus text format; `GET /health` answers ok.
  - **field `HANG_MILLIS`** of type `long`: How long `ProviderMode#HANG` stays silent unless `#hangMillis(long)` says otherwise.
    - Declaration: `public static final long HANG_MILLIS = 10_000`
  - **field `DEFAULT_SLOW_MILLIS`** of type `long`, starting as `5_000`
    - Declaration: `private static final long DEFAULT_SLOW_MILLIS = 5_000`
  - **field `callsReceived`** of type `AtomicInteger`, starting as `new AtomicInteger()`
    - Declaration: `private final AtomicInteger callsReceived = new AtomicInteger()`
  - **field `inFlight`** of type `AtomicInteger`, starting as `new AtomicInteger()`
    - Declaration: `private final AtomicInteger inFlight = new AtomicInteger()`
  - **field `maxInFlight`** of type `AtomicInteger`, starting as `new AtomicInteger()`
    - Declaration: `private final AtomicInteger maxInFlight = new AtomicInteger()`
  - **field `slowMillis`** of type `AtomicLong`, starting as `new AtomicLong(DEFAULT_SLOW_MILLIS)`
    - Declaration: `private final AtomicLong slowMillis = new AtomicLong(DEFAULT_SLOW_MILLIS)`
  - **field `failNext`** of type `AtomicInteger`, starting as `new AtomicInteger()`
    - Declaration: `private final AtomicInteger failNext = new AtomicInteger()`
  - **field `hangMillis`** of type `AtomicLong`, starting as `new AtomicLong(HANG_MILLIS)`
    - Declaration: `private final AtomicLong hangMillis = new AtomicLong(HANG_MILLIS)`
  - **field `mode`** of type `ProviderMode`, starting as `ProviderMode.NORMAL`
    - Declaration: `private volatile ProviderMode mode = ProviderMode.NORMAL`
  - **field `server`** of type `HttpServer`, set once when the object is built.
    - Declaration: `private final HttpServer server`
  - **method `FakeProvider`**: Creates the provider; call `#start()` to accept requests.
    - Signature: `public FakeProvider(int port) throws IOException`
    - input `port`: the port to listen on, 0 for any free port
    - throws `IOException`: if the port cannot be bound
    - What the body does, in order:
      - sets `server` to `HttpServer.create(new InetSocketAddress(port), 0)`
      - calls `server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())`
      - calls `server.createContext("/quotes/", this::quote)`
      - calls `server.createContext("/admin/mode", this::setMode)`
      - calls `server.createContext( "/admin/stats", exchange -> reply(exchange, 200, "application/json", statsJson()))`
      - calls `server.createContext("/admin/reset", this::reset)`
      - calls `server.createContext( "/metrics", exchange -> reply(exchange, 200, "text/plain; version=0.0.4", metricsText()))`
      - calls `server.createContext("/health", exchange -> reply(exchange, 200, "text/plain", "ok"))`
  - **method `start`**: Starts accepting requests.
    - Signature: `public void start()`
    - What the body does, in order:
      - calls `server.start()`
  - **method `port`**: Returns the port actually bound.
    - Signature: `public int port()`
    - gives back: the port
    - What the body does, in order:
      - returns `server.getAddress().getPort()`
  - **method `mode`**: Switches the mode directly, for tests that run the provider in the same JVM.
    - Signature: `public void mode(ProviderMode newMode)`
    - input `newMode`: the mode to answer with from now on
    - What the body does, in order:
      - sets `mode` to `newMode`
  - **method `hangMillis`**: Sets how long `ProviderMode#HANG` stays silent, so tests do not leave ten-second calls behind.
    - Signature: `public void hangMillis(long millis)`
    - input `millis`: the silence in milliseconds
    - What the body does, in order:
      - calls `hangMillis.set(millis)`
  - **method `failNext`**: Makes the next calls answer 503 whatever the mode is, then return to the mode. This is how a request that fails twice and succeeds on the third attempt is produced.
    - Signature: `public void failNext(int calls)`
    - input `calls`: how many calls to fail
    - What the body does, in order:
      - calls `failNext.set(calls)`
  - **method `slowMillis`**: Sets how long `ProviderMode#SLOW` waits before answering.
    - Signature: `public void slowMillis(long millis)`
    - input `millis`: the delay in milliseconds
    - What the body does, in order:
      - calls `slowMillis.set(millis)`
  - **method `callsReceived`**: Returns how many quote requests have arrived since the last reset.
    - Signature: `public int callsReceived()`
    - gives back: the count
    - What the body does, in order:
      - returns `callsReceived.get()`
  - **method `inFlight`**: Returns how many quote requests are being served right now.
    - Signature: `public int inFlight()`
    - gives back: the count
    - What the body does, in order:
      - returns `inFlight.get()`
  - **method `maxInFlight`**: Returns the most quote requests that were ever being served at the same moment since the last reset.
    - Signature: `public int maxInFlight()`
    - gives back: the maximum
    - What the body does, in order:
      - returns `maxInFlight.get()`
  - **method `resetCounters`**: Zeroes the counters.
    - Signature: `public void resetCounters()`
    - What the body does, in order:
      - calls `callsReceived.set(0)`
      - calls `maxInFlight.set(inFlight.get())`
  - **method `close`**: the signature is `void close()`.
    - Signature: `public void close()`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - calls `server.stop(1)`
  - **method `quote`**: the signature is `void quote(HttpExchange exchange) throws IOException`.
    - Signature: `private void quote(HttpExchange exchange) throws IOException`
    - What the body does, in order:
      - calls `callsReceived.incrementAndGet()`
      - calls `maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max)`
      - tries this:
        - when `failNext.getAndUpdate(remaining -> Math.max(remaining - 1, 0)) > 0` is true:
          - calls `reply(exchange, 503, "text/plain", "unavailable")`
          - returns, ending the method
        - chooses by `mode`:
          - runs `case FAIL503 -> reply(exchange, 503, "text/plain", "unavailable")`
          - runs `case HANG -> { sleep(hangMillis.get()); reply(exchange, 200, "application/json", "{}"); } case SLOW -> { sleep(slowMillis.get()); reply(exchange, 200, "application/json", quoteJson(exchange.getRequestURI())); } default -> reply( exchange, 200, "application/json", quoteJson(exchange.getRequestURI()))`
      - whatever happened above, finally:
        - calls `inFlight.decrementAndGet()`
  - **method `setMode`**: the signature is `void setMode(HttpExchange exchange) throws IOException`.
    - Signature: `private void setMode(HttpExchange exchange) throws IOException`
    - What the body does, in order:
      - when `!"PUT".equals(exchange.getRequestMethod())` is true:
        - calls `reply(exchange, 405, "text/plain", "use PUT")`
        - returns, ending the method
      - tries this:
        - creates the variable `query` (of type `String`) holding `exchange.getRequestURI().getQuery()`
        - sets `mode` to `ProviderMode.parse(queryValue(query, "mode"))`
        - creates the variable `delay` (of type `String`) holding `queryValue(query, "delayMs")`
        - when `delay != null` is true:
          - calls `slowMillis.set(Long.parseLong(delay))`
        - creates the variable `failures` (of type `String`) holding `queryValue(query, "failNext")`
        - calls `failNext.set(failures == null ? 0 : Integer.parseInt(failures))`
        - calls `JsonLog.info("mode set to " + mode + ", slow delay " + slowMillis.get() + " ms")`
        - calls `reply(exchange, 200, "application/json", statsJson())`
      - if this fails with `IllegalArgumentException exception`:
        - calls `reply(exchange, 400, "text/plain", exception.getMessage())`
  - **method `reset`**: the signature is `void reset(HttpExchange exchange) throws IOException`.
    - Signature: `private void reset(HttpExchange exchange) throws IOException`
    - What the body does, in order:
      - calls `resetCounters()`
      - calls `reply(exchange, 200, "application/json", statsJson())`
  - **method `statsJson`**: the signature is `String statsJson()`.
    - Signature: `private String statsJson()`
    - What the body does, in order:
      - returns `"{\"mode\":\"" + mode.name().toLowerCase(java.util.Locale.ROOT) + "\",\"slowDelayMs\":" + slowMillis.get() + ",\"callsReceived\":" + callsReceived.get() + ",\"inFlight\":" + inFlight.get() + ",\"maxInFlight\":" + maxInFlight.get() + "}"`
  - **method `metricsText`**: the signature is `String metricsText()`.
    - Signature: `private String metricsText()`
    - What the body does, in order:
      - returns `"# TYPE provider_calls_received_total counter\nprovider_calls_received_total " + callsReceived.get() + "\n# TYPE provider_in_flight gauge\nprovider_in_flight " + inFlight.get() + "\n# TYPE provider_in_flight_max gauge\nprovider_in_flight_max " + maxInFlight.get() + "\n"`
  - **method `quoteJson`**: the signature is `static String quoteJson(URI uri)`.
    - Signature: `private static String quoteJson(URI uri)`
    - What the body does, in order:
      - creates the variable `path` (of type `String`) holding `uri.getPath()`
      - creates the variable `sku` (of type `String`) holding `path.substring(path.lastIndexOf('/') + 1)`
      - creates the variable `price` (of type `BigDecimal`) holding `BigDecimal.valueOf(1000 + Math.floorMod(sku.hashCode(), 9000)) .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY)`
      - returns `"{\"price\":" + price.toPlainString() + "}"`
  - **method `queryValue`**: the signature is `static String queryValue(String query, String name)`.
    - Signature: `private static String queryValue(String query, String name)`
    - What the body does, in order:
      - when `query == null` is true:
        - returns `null`
      - repeats for `String pair : query.split("&")`:
        - creates the variable `parts` (of type `String[]`) holding `pair.split("=", 2)`
        - when `parts[0].equals(name) && parts.length == 2` is true:
          - returns `parts[1]`
      - returns `null`
  - **method `sleep`**: the signature is `static void sleep(long millis)`.
    - Signature: `private static void sleep(long millis)`
    - What the body does, in order:
      - tries this:
        - calls `Thread.sleep(millis)`
      - if this fails with `InterruptedException interrupted`:
        - calls `Thread.currentThread().interrupt()`
  - **method `reply`**: the signature is `static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException`.
    - Signature: `private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException`
    - What the body does, in order:
      - creates the variable `bytes` (of type `byte[]`) holding `body.getBytes(StandardCharsets.UTF_8)`
      - calls `exchange.getResponseHeaders().set("Content-Type", contentType)`
      - tries this:
        - calls `exchange.sendResponseHeaders(status, bytes.length)`
        - tries this (managing `OutputStream out = exchange.getResponseBody()` so it is closed afterwards):
          - calls `out.write(bytes)`
      - if this fails with `IOException clientGone`:
        - calls `JsonLog.info( "client closed the connection before the answer: " + clientGone.getMessage())`
      - whatever happened above, finally:
        - calls `exchange.close()`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **implements**: `implements X` promises that the class provides every method of the interface `X`.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **switch**: `switch` chooses one branch by the value of an expression.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.


### `provider/src/main/java/dev/plainround/provider/JsonLog.java`

Package `dev.plainround.provider`. One JSON object per line on standard output: level, message and time, nothing else.

The whole file, as it is in the repository:

```java
package dev.plainround.provider;

import java.time.Instant;

/** One JSON object per line on standard output: level, message and time, nothing else. */
final class JsonLog {

    private JsonLog() {}

    static void info(String message) {
        write("INFO", message);
    }

    static void error(String message) {
        write("ERROR", message);
    }

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

    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
```

Reading it, one piece at a time:

- **`JsonLog`**, a class. One JSON object per line on standard output: level, message and time, nothing else.
  - **method `JsonLog`**: the signature is `JsonLog()`.
    - Signature: `private JsonLog()`
  - **method `info`**: the signature is `static void info(String message)`.
    - Signature: `static void info(String message)`
    - What the body does, in order:
      - calls `write("INFO", message)`
  - **method `error`**: the signature is `static void error(String message)`.
    - Signature: `static void error(String message)`
    - What the body does, in order:
      - calls `write("ERROR", message)`
  - **method `write`**: the signature is `static void write(String level, String message)`.
    - Signature: `private static void write(String level, String message)`
    - What the body does, in order:
      - calls `System.out.println( "{\"@timestamp\":\"" + Instant.now() + "\",\"level\":\"" + level + "\",\"service\":\"provider\"" + ",\"message\":\"" + escape(message) + "\"}")`
  - **method `escape`**: the signature is `static String escape(String text)`.
    - Signature: `static String escape(String text)`
    - What the body does, in order:
      - returns `text.replace("\\", "\\\\") .replace("\"", "\\\"") .replace("\n", "\\n") .replace("\r", "\\r")`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.


### `provider/src/main/java/dev/plainround/provider/ProviderMain.java`

Package `dev.plainround.provider`. Starts the fake provider on `PORT` (default 8081) and stops it when the process is told to.

The whole file, as it is in the repository:

```java
package dev.plainround.provider;

import java.io.IOException;

/**
 * Starts the fake provider on {@code PORT} (default 8081) and stops it when the process is told to.
 */
public final class ProviderMain {

    private ProviderMain() {}

    /**
     * Runs the provider until the JVM receives a termination signal.
     *
     * @param args ignored; configuration comes from the environment
     * @throws IOException if the port cannot be bound
     */
    public static void main(String[] args) throws IOException {
        FakeProvider provider =
                start(Integer.parseInt(System.getenv().getOrDefault("PORT", "8081")));
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    JsonLog.info("shutting down");
                                    provider.close();
                                }));
    }

    /**
     * Creates and starts a provider.
     *
     * @param port the port to listen on, 0 for any free port
     * @return the running provider
     * @throws IOException if the port cannot be bound
     */
    static FakeProvider start(int port) throws IOException {
        FakeProvider provider = new FakeProvider(port);
        provider.start();
        JsonLog.info("fake provider listening on port " + provider.port());
        return provider;
    }
}
```

Reading it, one piece at a time:

- **`ProviderMain`**, a class. Starts the fake provider on `PORT` (default 8081) and stops it when the process is told to.
  - **method `ProviderMain`**: the signature is `ProviderMain()`.
    - Signature: `private ProviderMain()`
  - **method `main`**: Runs the provider until the JVM receives a termination signal.
    - Signature: `public static void main(String[] args) throws IOException`
    - input `args`: ignored; configuration comes from the environment
    - throws `IOException`: if the port cannot be bound
    - What the body does, in order:
      - creates the variable `provider` (of type `FakeProvider`) holding `start(Integer.parseInt(System.getenv().getOrDefault("PORT", "8081")))`
      - calls `Runtime.getRuntime() .addShutdownHook( new Thread( () -> { JsonLog.info("shutting down"); provider.close(); }))`
  - **method `start`**: Creates and starts a provider.
    - Signature: `static FakeProvider start(int port) throws IOException`
    - input `port`: the port to listen on, 0 for any free port
    - gives back: the running provider
    - throws `IOException`: if the port cannot be bound
    - What the body does, in order:
      - creates the variable `provider` (of type `FakeProvider`) holding `new FakeProvider(port)`
      - calls `provider.start()`
      - calls `JsonLog.info("fake provider listening on port " + provider.port())`
      - returns `provider`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.


### `provider/src/main/java/dev/plainround/provider/ProviderMode.java`

Package `dev.plainround.provider`. How the fake provider answers a quote request.

The whole file, as it is in the repository:

```java
package dev.plainround.provider;

import java.util.Locale;

/** How the fake provider answers a quote request. */
public enum ProviderMode {

    /** Answers 200 with a price at once. */
    NORMAL,

    /** Answers 200 with a price after a configurable delay (default 5 seconds). */
    SLOW,

    /** Answers 503 at once, the way an overloaded service sheds load. */
    FAIL503,

    /**
     * Accepts the connection and then does not answer for ten seconds, far past any sane read
     * timeout.
     */
    HANG;

    /**
     * Parses a mode name, ignoring case.
     *
     * @param text the name, such as {@code fail503}
     * @return the mode
     * @throws IllegalArgumentException if the name is not one of the four modes
     */
    public static ProviderMode parse(String text) {
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "mode must be one of normal, slow, fail503, hang", exception);
        }
    }
}
```

Reading it, one piece at a time:

- **`ProviderMode`**, a enum. How the fake provider answers a quote request.
  - **field `HANG`** of type `FAIL503,`: Answers 200 with a price at once.
    - Declaration: `NORMAL, SLOW, FAIL503, HANG`
  - **method `parse`**: Parses a mode name, ignoring case.
    - Signature: `public static ProviderMode parse(String text)`
    - input `text`: the name, such as `fail503`
    - gives back: the mode
    - throws `IllegalArgumentException`: if the name is not one of the four modes
    - What the body does, in order:
      - tries this:
        - returns `valueOf(text.trim().toUpperCase(Locale.ROOT))`
      - if this fails with `IllegalArgumentException | NullPointerException exception`:
        - throws a new `IllegalArgumentException` created with `"mode must be one of normal, slow, fail503, hang", exception`

Language features in this file, explained:

- **enum**: An `enum` is a type with a fixed list of named values.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **public**: `public` lets any class use the member.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.


### `loadgen/src/main/java/dev/plainround/loadgen/LoadMain.java`

Package `dev.plainround.loadgen`. Command line: `java -jar loadgen.jar --target http://host/price/{sku` --skus 10 --rps 100 --seconds 30 [--side http://host/skus/sku-1 --side-rps 10]}.

The whole file, as it is in the repository:

```java
package dev.plainround.loadgen;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line: {@code java -jar loadgen.jar --target http://host/price/{sku} --skus 10 --rps 100
 * --seconds 30 [--side http://host/skus/sku-1 --side-rps 10]}. Prints one JSON report per target on
 * its own line.
 */
public final class LoadMain {

    private LoadMain() {}

    /**
     * Runs the load and prints the reports.
     *
     * @param args the options described above
     * @throws InterruptedException if the run is interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        Map<String, String> options = parse(args);
        int rate = Integer.parseInt(options.getOrDefault("rps", "100"));
        int seconds = Integer.parseInt(options.getOrDefault("seconds", "30"));
        int skus = Integer.parseInt(options.getOrDefault("skus", "10"));
        List<Thread> runs = new ArrayList<>();
        List<Report> reports = java.util.Collections.synchronizedList(new ArrayList<>());
        String target = require(options, "target");
        runs.add(start(() -> reports.add(run("price", target, skus, rate, seconds))));
        if (options.containsKey("side")) {
            int sideRate = Integer.parseInt(options.getOrDefault("side-rps", "10"));
            runs.add(
                    start(
                            () ->
                                    reports.add(
                                            run(
                                                    "side",
                                                    options.get("side"),
                                                    1,
                                                    sideRate,
                                                    seconds))));
        }
        for (Thread run : runs) {
            run.join();
        }
        reports.stream().map(Report::toJson).forEach(System.out::println);
    }

    private static Report run(String name, String template, int skus, int rate, int seconds) {
        try {
            return new LoadRunner()
                    .run(
                            name,
                            number -> URI.create(template.replace("{sku}", "sku-" + number % skus)),
                            rate,
                            seconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Report.of(name, new long[0], 0, 0);
        }
    }

    private static Thread start(Runnable work) {
        return Thread.ofPlatform().start(work);
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index + 1 < args.length; index += 2) {
            options.put(args[index].replaceFirst("^--", ""), args[index + 1]);
        }
        return options;
    }
}
```

Reading it, one piece at a time:

- **`LoadMain`**, a class. Command line: `java -jar loadgen.jar --target http://host/price/{sku` --skus 10 --rps 100 --seconds 30 [--side http://host/skus/sku-1 --side-rps 10]}. Prints one JSON report per target on its own line.
  - **method `LoadMain`**: the signature is `LoadMain()`.
    - Signature: `private LoadMain()`
  - **method `main`**: Runs the load and prints the reports.
    - Signature: `public static void main(String[] args) throws InterruptedException`
    - input `args`: the options described above
    - throws `InterruptedException`: if the run is interrupted
    - What the body does, in order:
      - creates the variable `options` (of type `Map<String, String>`) holding `parse(args)`
      - creates the variable `rate` (of type `int`) holding `Integer.parseInt(options.getOrDefault("rps", "100"))`
      - creates the variable `seconds` (of type `int`) holding `Integer.parseInt(options.getOrDefault("seconds", "30"))`
      - creates the variable `skus` (of type `int`) holding `Integer.parseInt(options.getOrDefault("skus", "10"))`
      - creates the variable `runs` (of type `List<Thread>`) holding `new ArrayList<>()`
      - creates the variable `reports` (of type `List<Report>`) holding `java.util.Collections.synchronizedList(new ArrayList<>())`
      - creates the variable `target` (of type `String`) holding `require(options, "target")`
      - calls `runs.add(start(() -> reports.add(run("price", target, skus, rate, seconds))))`
      - when `options.containsKey("side")` is true:
        - creates the variable `sideRate` (of type `int`) holding `Integer.parseInt(options.getOrDefault("side-rps", "10"))`
        - calls `runs.add( start( () -> reports.add( run( "side", options.get("side"), 1, sideRate, seconds))))`
      - repeats for `Thread run : runs`:
        - calls `run.join()`
      - calls `reports.stream().map(Report::toJson).forEach(System.out::println)`
  - **method `run`**: the signature is `static Report run(String name, String template, int skus, int rate, int seconds)`.
    - Signature: `private static Report run(String name, String template, int skus, int rate, int seconds)`
    - What the body does, in order:
      - tries this:
        - returns `new LoadRunner() .run( name, number -> URI.create(template.replace("{sku}", "sku-" + number % skus)), rate, seconds)`
      - if this fails with `InterruptedException interrupted`:
        - calls `Thread.currentThread().interrupt()`
        - returns `Report.of(name, new long[0], 0, 0)`
  - **method `start`**: the signature is `static Thread start(Runnable work)`.
    - Signature: `private static Thread start(Runnable work)`
    - What the body does, in order:
      - returns `Thread.ofPlatform().start(work)`
  - **method `require`**: the signature is `static String require(Map<String, String> options, String name)`.
    - Signature: `private static String require(Map<String, String> options, String name)`
    - What the body does, in order:
      - creates the variable `value` (of type `String`) holding `options.get(name)`
      - when `value == null` is true:
        - throws a new `IllegalArgumentException` created with `"missing --" + name`
      - returns `value`
  - **method `parse`**: the signature is `static Map<String, String> parse(String[] args)`.
    - Signature: `static Map<String, String> parse(String[] args)`
    - What the body does, in order:
      - creates the variable `options` (of type `Map<String, String>`) holding `new HashMap<>()`
      - repeats for `int index = 0; index + 1 < args.length; index += 2`:
        - calls `options.put(args[index].replaceFirst("^--", ""), args[index + 1])`
      - returns `options`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **Map**: A `Map` stores values under keys, like a dictionary.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.
- **Stream**: A stream processes a collection step by step: `map` converts each item, `filter` keeps some, `toList` collects the result.


### `loadgen/src/main/java/dev/plainround/loadgen/LoadRunner.java`

Package `dev.plainround.loadgen`. Sends requests at a fixed rate whether or not the earlier ones have come back (an open loop), so a slow target shows up as latency instead of quietly lowering the load.

The whole file, as it is in the repository:

```java
package dev.plainround.loadgen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.LongStream;

/**
 * Sends requests at a fixed rate whether or not the earlier ones have come back (an open loop), so
 * a slow target shows up as latency instead of quietly lowering the load.
 */
public final class LoadRunner {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * Runs one target for a fixed time.
     *
     * @param name a label for the report
     * @param urlForRequest maps the request number (0, 1, 2, ...) to the URL to call
     * @param requestsPerSecond the rate to hold
     * @param seconds how long to send
     * @return the report, produced after every answer (or timeout) is in
     * @throws InterruptedException if the run is interrupted
     */
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

    private void send(
            URI uri,
            ConcurrentLinkedQueue<Long> latencies,
            AtomicInteger errors,
            AtomicInteger stale) {
        long started = System.nanoTime();
        boolean failed = false;
        boolean wasStale = false;
        try {
            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).build(),
                            HttpResponse.BodyHandlers.ofString());
            failed = response.statusCode() / 100 != 2;
            wasStale = !failed && response.body().contains("\"stale\":true");
        } catch (IOException exception) {
            failed = true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failed = true;
        }
        latencies.add(System.nanoTime() - started);
        errors.addAndGet(failed ? 1 : 0);
        stale.addAndGet(wasStale ? 1 : 0);
    }

    private static void sleepUntil(long deadlineNanos) throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static long[] toArray(ConcurrentLinkedQueue<Long> latencies) {
        return List.copyOf(latencies).stream().flatMapToLong(LongStream::of).toArray();
    }
}
```

Reading it, one piece at a time:

- **`LoadRunner`**, a class. Sends requests at a fixed rate whether or not the earlier ones have come back (an open loop), so a slow target shows up as latency instead of quietly lowering the load.
  - **field `REQUEST_TIMEOUT`** of type `Duration`, starting as `Duration.ofSeconds(15)`
    - Declaration: `private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15)`
  - **field `client`** of type `HttpClient`, starting as `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()`
    - Declaration: `private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()`
  - **method `run`**: Runs one target for a fixed time.
    - Signature: `public Report run( String name, IntFunction<URI> urlForRequest, int requestsPerSecond, int seconds) throws InterruptedException`
    - input `name`: a label for the report
    - input `urlForRequest`: maps the request number (0, 1, 2, ...) to the URL to call
    - input `requestsPerSecond`: the rate to hold
    - input `seconds`: how long to send
    - gives back: the report, produced after every answer (or timeout) is in
    - throws `InterruptedException`: if the run is interrupted
    - What the body does, in order:
      - creates the variable `latencies` (of type `ConcurrentLinkedQueue<Long>`) holding `new ConcurrentLinkedQueue<>()`
      - creates the variable `errors` (of type `AtomicInteger`) holding `new AtomicInteger()`
      - creates the variable `stale` (of type `AtomicInteger`) holding `new AtomicInteger()`
      - creates the variable `total` (of type `int`) holding `requestsPerSecond * seconds`
      - creates the variable `startNanos` (of type `long`) holding `System.nanoTime()`
      - tries this (managing `ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()` so it is closed afterwards):
        - repeats for `int number = 0; number < total; number++`:
          - calls `sleepUntil(startNanos + number * (1_000_000_000L / requestsPerSecond))`
          - creates the variable `uri` (of type `URI`) holding `urlForRequest.apply(number)`
          - calls `pool.submit(() -> send(uri, latencies, errors, stale))`
        - calls `pool.shutdown()`
        - calls `pool.awaitTermination(REQUEST_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS)`
      - returns `Report.of(name, toArray(latencies), errors.get(), stale.get())`
  - **method `send`**: the signature is `void send( URI uri, ConcurrentLinkedQueue<Long> latencies, AtomicInteger errors, AtomicInteger stale)`.
    - Signature: `private void send( URI uri, ConcurrentLinkedQueue<Long> latencies, AtomicInteger errors, AtomicInteger stale)`
    - What the body does, in order:
      - creates the variable `started` (of type `long`) holding `System.nanoTime()`
      - creates the variable `failed` (of type `boolean`) holding `false`
      - creates the variable `wasStale` (of type `boolean`) holding `false`
      - tries this:
        - creates the variable `response` (of type `HttpResponse<String>`) holding `client.send( HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())`
        - sets `failed` to `response.statusCode() / 100 != 2`
        - sets `wasStale` to `!failed && response.body().contains("\"stale\":true")`
      - if this fails with `IOException exception`:
        - sets `failed` to `true`
      - if this fails with `InterruptedException exception`:
        - calls `Thread.currentThread().interrupt()`
        - sets `failed` to `true`
      - calls `latencies.add(System.nanoTime() - started)`
      - calls `errors.addAndGet(failed ? 1 : 0)`
      - calls `stale.addAndGet(wasStale ? 1 : 0)`
  - **method `sleepUntil`**: the signature is `static void sleepUntil(long deadlineNanos) throws InterruptedException`.
    - Signature: `private static void sleepUntil(long deadlineNanos) throws InterruptedException`
    - What the body does, in order:
      - creates the variable `remaining` (of type `long`) holding `deadlineNanos - System.nanoTime()`
      - when `remaining > 0` is true:
        - calls `TimeUnit.NANOSECONDS.sleep(remaining)`
  - **method `toArray`**: the signature is `static long[] toArray(ConcurrentLinkedQueue<Long> latencies)`.
    - Signature: `private static long[] toArray(ConcurrentLinkedQueue<Long> latencies)`
    - What the body does, in order:
      - returns `List.copyOf(latencies).stream().flatMapToLong(LongStream::of).toArray()`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.
- **Stream**: A stream processes a collection step by step: `map` converts each item, `filter` keeps some, `toList` collects the result.


### `loadgen/src/main/java/dev/plainround/loadgen/Report.java`

Package `dev.plainround.loadgen`. The outcome of one load run against one target.

The whole file, as it is in the repository:

```java
package dev.plainround.loadgen;

import java.util.Arrays;
import java.util.Locale;

/**
 * The outcome of one load run against one target.
 *
 * @param name a label for the target
 * @param requests how many requests were sent
 * @param errors how many answered something other than 2xx, or not at all
 * @param stale how many 2xx answers said {@code "stale":true}
 * @param p50Millis median latency in milliseconds
 * @param p99Millis 99th-percentile latency in milliseconds
 */
public record Report(
        String name, int requests, int errors, int stale, double p50Millis, double p99Millis) {

    /**
     * Builds a report from raw samples.
     *
     * @param name a label for the target
     * @param latenciesNanos one latency per request, in nanoseconds
     * @param errors how many requests failed
     * @param stale how many answers were stale
     * @return the report
     */
    public static Report of(String name, long[] latenciesNanos, int errors, int stale) {
        long[] sorted = latenciesNanos.clone();
        Arrays.sort(sorted);
        return new Report(
                name, sorted.length, errors, stale, percentile(sorted, 50), percentile(sorted, 99));
    }

    /**
     * Returns the share of requests that failed.
     *
     * @return a fraction between 0 and 1
     */
    public double errorRate() {
        return requests == 0 ? 0 : (double) errors / requests;
    }

    /**
     * Renders the report as one JSON object on one line.
     *
     * @return the JSON text
     */
    public String toJson() {
        return String.format(
                Locale.ROOT,
                "{\"name\":\"%s\",\"requests\":%d,\"errors\":%d,\"errorRate\":%.4f,\"stale\":%d,\"p50Ms\":%.1f,\"p99Ms\":%.1f}",
                name,
                requests,
                errors,
                errorRate(),
                stale,
                p50Millis,
                p99Millis);
    }

    private static double percentile(long[] sorted, int percent) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1] / 1_000_000.0;
    }
}
```

Reading it, one piece at a time:

- **`Report`**, a record carrying `String name, int requests, int errors, int stale, double p50Millis, double p99Millis`. The outcome of one load run against one target.
  - **method `of`**: Builds a report from raw samples.
    - Signature: `public static Report of(String name, long[] latenciesNanos, int errors, int stale)`
    - input `name`: a label for the target
    - input `latenciesNanos`: one latency per request, in nanoseconds
    - input `errors`: how many requests failed
    - input `stale`: how many answers were stale
    - gives back: the report
    - What the body does, in order:
      - creates the variable `sorted` (of type `long[]`) holding `latenciesNanos.clone()`
      - calls `Arrays.sort(sorted)`
      - returns `new Report( name, sorted.length, errors, stale, percentile(sorted, 50), percentile(sorted, 99))`
  - **method `errorRate`**: Returns the share of requests that failed.
    - Signature: `public double errorRate()`
    - gives back: a fraction between 0 and 1
    - What the body does, in order:
      - returns `requests == 0 ? 0 : (double) errors / requests`
  - **method `toJson`**: Renders the report as one JSON object on one line.
    - Signature: `public String toJson()`
    - gives back: the JSON text
    - What the body does, in order:
      - returns `String.format( Locale.ROOT, "{\"name\":\"%s\",\"requests\":%d,\"errors\":%d,\"errorRate\":%.4f,\"stale\":%d,\"p50Ms\":%.1f,\"p99Ms\":%.1f}", name, requests, errors, errorRate(), stale, p50Millis, p99Millis)`
  - **method `percentile`**: the signature is `static double percentile(long[] sorted, int percent)`.
    - Signature: `private static double percentile(long[] sorted, int percent)`
    - What the body does, in order:
      - when `sorted.length == 0` is true:
        - returns `0`
      - creates the variable `rank` (of type `int`) holding `(int) Math.ceil(percent / 100.0 * sorted.length)`
      - returns `sorted[Math.max(rank, 1) - 1] / 1_000_000.0`

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/PriceServiceApplication.java`

Package `dev.plainround.price`. Entry point of the price service.

The whole file, as it is in the repository:

```java
package dev.plainround.price;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the price service. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PriceServiceApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments, passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(PriceServiceApplication.class, args);
    }
}
```

Reading it, one piece at a time:

- **`PriceServiceApplication`**, a class. Entry point of the price service.
  - annotation `@SpringBootApplication`: marks the starting class: it turns on auto-configuration and scans this package and below for components
  - annotation `@ConfigurationPropertiesScan`: finds every `@ConfigurationProperties` class in this package and registers it
  - **method `main`**: Starts the application.
    - Signature: `public static void main(String[] args)`
    - input `args`: command-line arguments, passed to Spring Boot
    - What the body does, in order:
      - calls `SpringApplication.run(PriceServiceApplication.class, args)`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@SpringBootApplication`: marks the starting class: it turns on auto-configuration and scans this package and below for components.
- `@ConfigurationPropertiesScan`: finds every `@ConfigurationProperties` class in this package and registers it.

Language features in this file, explained:

- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/application/CatalogService.java`

Package `dev.plainround.price.application`. Reads the product catalog, which does not depend on the provider.

The whole file, as it is in the repository:

```java
package dev.plainround.price.application;

import dev.plainround.price.domain.Catalog;
import dev.plainround.price.domain.CatalogEntry;
import dev.plainround.price.domain.SkuNotFound;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reads the product catalog, which does not depend on the provider. */
@Service
public class CatalogService {

    private final Catalog catalog;

    /**
     * Wires the service.
     *
     * @param catalog the products
     */
    public CatalogService(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Lists every product.
     *
     * @return the entries
     */
    public List<CatalogEntry> list() {
        return catalog.all();
    }

    /**
     * Returns one product.
     *
     * @param sku the product code
     * @return the entry
     * @throws SkuNotFound if there is none
     */
    public CatalogEntry get(String sku) {
        return catalog.find(sku).orElseThrow(() -> new SkuNotFound(sku));
    }
}
```

Reading it, one piece at a time:

- **`CatalogService`**, a class. Reads the product catalog, which does not depend on the provider.
  - annotation `@Service`: tells Spring to create one shared instance of this business-logic class at startup
  - **field `catalog`** of type `Catalog`, set once when the object is built.
    - Declaration: `private final Catalog catalog`
  - **method `CatalogService`**: Wires the service.
    - Signature: `public CatalogService(Catalog catalog)`
    - input `catalog`: the products
    - What the body does, in order:
      - stores `catalog` in the field `catalog`
  - **method `list`**: Lists every product.
    - Signature: `public List<CatalogEntry> list()`
    - gives back: the entries
    - What the body does, in order:
      - returns `catalog.all()`
  - **method `get`**: Returns one product.
    - Signature: `public CatalogEntry get(String sku)`
    - input `sku`: the product code
    - gives back: the entry
    - throws `SkuNotFound`: if there is none
    - What the body does, in order:
      - returns `catalog.find(sku).orElseThrow(() -> new SkuNotFound(sku))`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Service`: tells Spring to create one shared instance of this business-logic class at startup.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.


### `price-service/src/main/java/dev/plainround/price/application/PriceService.java`

Package `dev.plainround.price.application`. Prices a SKU: a fresh quote when the provider answers, the last good one, labeled stale, when it does not.

The whole file, as it is in the repository:

```java
package dev.plainround.price.application;

import dev.plainround.price.domain.NoPriceAvailable;
import dev.plainround.price.domain.PriceResult;
import dev.plainround.price.domain.ProviderFailure;
import dev.plainround.price.domain.Quote;
import dev.plainround.price.domain.QuoteCache;
import dev.plainround.price.domain.QuoteSource;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prices a SKU: a fresh quote when the provider answers, the last good one, labeled stale, when it
 * does not.
 */
@Service
public class PriceService {

    private static final Logger LOG = LoggerFactory.getLogger(PriceService.class);

    private final QuoteSource source;
    private final QuoteCache cache;
    private final Clock clock;

    /**
     * Wires the service.
     *
     * @param source where fresh quotes come from
     * @param cache the last good quotes
     * @param clock the time source
     */
    public PriceService(QuoteSource source, QuoteCache cache, Clock clock) {
        this.source = source;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Prices a SKU.
     *
     * @param sku the product code
     * @return the price, fresh or stale
     * @throws NoPriceAvailable if the provider failed and nothing recent is remembered
     */
    public PriceResult price(String sku) {
        try {
            Quote quote = source.fetch(sku);
            cache.put(sku, quote);
            return PriceResult.fresh(quote);
        } catch (ProviderFailure failure) {
            return fallback(sku, failure);
        }
    }

    private PriceResult fallback(String sku, ProviderFailure failure) {
        LOG.warn("provider failed sku={} reason={}", sku, failure.getClass().getSimpleName());
        return cache.get(sku)
                .map(quote -> PriceResult.stale(quote, clock.instant()))
                .orElseThrow(() -> new NoPriceAvailable(sku, failure));
    }
}
```

Reading it, one piece at a time:

- **`PriceService`**, a class. Prices a SKU: a fresh quote when the provider answers, the last good one, labeled stale, when it does not.
  - annotation `@Service`: tells Spring to create one shared instance of this business-logic class at startup
  - **field `LOG`** of type `Logger`, starting as `LoggerFactory.getLogger(PriceService.class)`
    - Declaration: `private static final Logger LOG = LoggerFactory.getLogger(PriceService.class)`
  - **field `source`** of type `QuoteSource`, set once when the object is built.
    - Declaration: `private final QuoteSource source`
  - **field `cache`** of type `QuoteCache`, set once when the object is built.
    - Declaration: `private final QuoteCache cache`
  - **field `clock`** of type `Clock`, set once when the object is built.
    - Declaration: `private final Clock clock`
  - **method `PriceService`**: Wires the service.
    - Signature: `public PriceService(QuoteSource source, QuoteCache cache, Clock clock)`
    - input `source`: where fresh quotes come from
    - input `cache`: the last good quotes
    - input `clock`: the time source
    - What the body does, in order:
      - stores `source` in the field `source`
      - stores `cache` in the field `cache`
      - stores `clock` in the field `clock`
  - **method `price`**: Prices a SKU.
    - Signature: `public PriceResult price(String sku)`
    - input `sku`: the product code
    - gives back: the price, fresh or stale
    - throws `NoPriceAvailable`: if the provider failed and nothing recent is remembered
    - What the body does, in order:
      - tries this:
        - creates the variable `quote` (of type `Quote`) holding `source.fetch(sku)`
        - calls `cache.put(sku, quote)`
        - returns `PriceResult.fresh(quote)`
      - if this fails with `ProviderFailure failure`:
        - returns `fallback(sku, failure)`
  - **method `fallback`**: the signature is `PriceResult fallback(String sku, ProviderFailure failure)`.
    - Signature: `private PriceResult fallback(String sku, ProviderFailure failure)`
    - What the body does, in order:
      - calls `LOG.warn("provider failed sku={} reason={}", sku, failure.getClass().getSimpleName())`
      - returns `cache.get(sku) .map(quote -> PriceResult.stale(quote, clock.instant())) .orElseThrow(() -> new NoPriceAvailable(sku, failure))`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Service`: tells Spring to create one shared instance of this business-logic class at startup.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.


### `price-service/src/main/java/dev/plainround/price/domain/Catalog.java`

Package `dev.plainround.price.domain`. Port: the products the shop sells.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

import java.util.List;
import java.util.Optional;

/** Port: the products the shop sells. */
public interface Catalog {

    /**
     * Lists every product.
     *
     * @return the entries
     */
    List<CatalogEntry> all();

    /**
     * Looks a product up.
     *
     * @param sku the product code
     * @return the entry, or empty if there is none
     */
    Optional<CatalogEntry> find(String sku);
}
```

Reading it, one piece at a time:

- **`Catalog`**, a interface. Port: the products the shop sells.
  - **method `all`**: Lists every product.
    - Signature: `List<CatalogEntry> all()`
    - gives back: the entries
  - **method `find`**: Looks a product up.
    - Signature: `Optional<CatalogEntry> find(String sku)`
    - input `sku`: the product code
    - gives back: the entry, or empty if there is none

Language features in this file, explained:

- **interface**: An `interface` lists method signatures without bodies. Any class that says it `implements` the interface must provide those methods, so callers can depend on the list alone.
- **public**: `public` lets any class use the member.
- **Optional**: `Optional<T>` is a box that holds either one `T` or nothing. It makes "may be missing" visible in the type instead of using null.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.


### `price-service/src/main/java/dev/plainround/price/domain/CatalogEntry.java`

Package `dev.plainround.price.domain`. A product the shop sells.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/**
 * A product the shop sells. Its lookup needs no provider, so it keeps answering while the provider
 * is down.
 *
 * @param sku the product code
 * @param name the display name
 */
public record CatalogEntry(String sku, String name) {}
```

Reading it, one piece at a time:

- **`CatalogEntry`**, a record carrying `String sku, String name`. A product the shop sells. Its lookup needs no provider, so it keeps answering while the provider is down.

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/domain/NoPriceAvailable.java`

Package `dev.plainround.price.domain`. The provider failed and there is no recent quote to fall back on.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/** The provider failed and there is no recent quote to fall back on. */
public class NoPriceAvailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProviderFailure failure;

    /**
     * Creates the error.
     *
     * @param sku the product code that could not be priced
     * @param failure why the provider could not answer
     */
    public NoPriceAvailable(String sku, ProviderFailure failure) {
        super("no price available for " + sku, failure);
        this.failure = failure;
    }

    /**
     * Returns why the provider could not answer.
     *
     * @return the provider failure
     */
    public ProviderFailure failure() {
        return failure;
    }
}
```

Reading it, one piece at a time:

- **`NoPriceAvailable`**, a class that extends `RuntimeException`. The provider failed and there is no recent quote to fall back on.
  - **field `serialVersionUID`** of type `long`, starting as `1L`
    - Declaration: `private static final long serialVersionUID = 1L`
  - **field `failure`** of type `ProviderFailure`, set once when the object is built.
    - Declaration: `private final transient ProviderFailure failure`
  - **method `NoPriceAvailable`**: Creates the error.
    - Signature: `public NoPriceAvailable(String sku, ProviderFailure failure)`
    - input `sku`: the product code that could not be priced
    - input `failure`: why the provider could not answer
    - What the body does, in order:
      - calls the parent constructor: `super("no price available for " + sku, failure)`
      - stores `failure` in the field `failure`
  - **method `failure`**: Returns why the provider could not answer.
    - Signature: `public ProviderFailure failure()`
    - gives back: the provider failure
    - What the body does, in order:
      - returns `failure`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.


### `price-service/src/main/java/dev/plainround/price/domain/PriceResult.java`

Package `dev.plainround.price.domain`. What the price API answers: the price, whether it is the last one we knew rather than a fresh one, and its age.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * What the price API answers: the price, whether it is the last one we knew rather than a fresh
 * one, and its age.
 *
 * @param price the unit price
 * @param stale true when the provider failed and this is its last good answer
 * @param ageSeconds how many seconds ago the provider gave this price, 0 for a fresh one
 */
public record PriceResult(BigDecimal price, boolean stale, long ageSeconds) {

    /**
     * Wraps a quote that has just been fetched.
     *
     * @param quote the fresh quote
     * @return a result that is not stale
     */
    public static PriceResult fresh(Quote quote) {
        return new PriceResult(quote.price(), false, 0);
    }

    /**
     * Wraps the last good quote, labeled stale with its age.
     *
     * @param quote the cached quote
     * @param now the current time
     * @return a result that is stale
     */
    public static PriceResult stale(Quote quote, Instant now) {
        return new PriceResult(
                quote.price(), true, Duration.between(quote.fetchedAt(), now).toSeconds());
    }
}
```

Reading it, one piece at a time:

- **`PriceResult`**, a record carrying `BigDecimal price, boolean stale, long ageSeconds`. What the price API answers: the price, whether it is the last one we knew rather than a fresh one, and its age.
  - **method `fresh`**: Wraps a quote that has just been fetched.
    - Signature: `public static PriceResult fresh(Quote quote)`
    - input `quote`: the fresh quote
    - gives back: a result that is not stale
    - What the body does, in order:
      - returns `new PriceResult(quote.price(), false, 0)`
  - **method `stale`**: Wraps the last good quote, labeled stale with its age.
    - Signature: `public static PriceResult stale(Quote quote, Instant now)`
    - input `quote`: the cached quote
    - input `now`: the current time
    - gives back: a result that is stale
    - What the body does, in order:
      - returns `new PriceResult( quote.price(), true, Duration.between(quote.fetchedAt(), now).toSeconds())`

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/domain/ProviderBusy.java`

Package `dev.plainround.price.domain`. Too many calls to the provider are already in flight; this one was refused at once instead of queueing.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/**
 * Too many calls to the provider are already in flight; this one was refused at once instead of
 * queueing.
 */
public final class ProviderBusy extends ProviderFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param cause the rejection from the concurrency limit
     */
    public ProviderBusy(Throwable cause) {
        super("too many calls to the provider are in flight", cause);
    }
}
```

Reading it, one piece at a time:

- **`ProviderBusy`**, a class that extends `ProviderFailure`. Too many calls to the provider are already in flight; this one was refused at once instead of queueing.
  - **field `serialVersionUID`** of type `long`, starting as `1L`
    - Declaration: `private static final long serialVersionUID = 1L`
  - **method `ProviderBusy`**: Creates the failure.
    - Signature: `public ProviderBusy(Throwable cause)`
    - input `cause`: the rejection from the concurrency limit
    - What the body does, in order:
      - calls the parent constructor: `super("too many calls to the provider are in flight", cause)`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.


### `price-service/src/main/java/dev/plainround/price/domain/ProviderFailure.java`

Package `dev.plainround.price.domain`. The provider could not give a price right now.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/**
 * The provider could not give a price right now. The three subtypes say why, and only one of them
 * is retried.
 */
public abstract sealed class ProviderFailure extends RuntimeException
        permits ProviderTimeout, ProviderUnavailable, ProviderBusy {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param message what went wrong
     * @param cause the underlying error, or null
     */
    protected ProviderFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Reading it, one piece at a time:

- **`ProviderFailure`**, a class that extends `RuntimeException permits ProviderTimeout, ProviderUnavailable, ProviderBusy`. The provider could not give a price right now. The three subtypes say why, and only one of them is retried.
  - **field `serialVersionUID`** of type `long`, starting as `1L`
    - Declaration: `private static final long serialVersionUID = 1L`
  - **method `ProviderFailure`**: Creates the failure.
    - Signature: `protected ProviderFailure(String message, Throwable cause)`
    - input `message`: what went wrong
    - input `cause`: the underlying error, or null
    - What the body does, in order:
      - calls the parent constructor: `super(message, cause)`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.
- **sealed**: A `sealed` type lists exactly which classes may extend it.


### `price-service/src/main/java/dev/plainround/price/domain/ProviderTimeout.java`

Package `dev.plainround.price.domain`. The provider did not answer within the read or connect timeout.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/**
 * The provider did not answer within the read or connect timeout. Never retried: it only adds load.
 */
public final class ProviderTimeout extends ProviderFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param cause the timeout from the HTTP client
     */
    public ProviderTimeout(Throwable cause) {
        super("the provider did not answer in time", cause);
    }
}
```

Reading it, one piece at a time:

- **`ProviderTimeout`**, a class that extends `ProviderFailure`. The provider did not answer within the read or connect timeout. Never retried: it only adds load.
  - **field `serialVersionUID`** of type `long`, starting as `1L`
    - Declaration: `private static final long serialVersionUID = 1L`
  - **method `ProviderTimeout`**: Creates the failure.
    - Signature: `public ProviderTimeout(Throwable cause)`
    - input `cause`: the timeout from the HTTP client
    - What the body does, in order:
      - calls the parent constructor: `super("the provider did not answer in time", cause)`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.


### `price-service/src/main/java/dev/plainround/price/domain/ProviderUnavailable.java`

Package `dev.plainround.price.domain`. The provider said it cannot serve (503), could not be reached, or sent nothing usable.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/** The provider said it cannot serve (503), could not be reached, or sent nothing usable. */
public final class ProviderUnavailable extends ProviderFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param message what went wrong
     */
    public ProviderUnavailable(String message) {
        super(message, null);
    }

    /**
     * Creates the failure with its cause.
     *
     * @param message what went wrong
     * @param cause the underlying error
     */
    public ProviderUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Reading it, one piece at a time:

- **`ProviderUnavailable`**, a class that extends `ProviderFailure`. The provider said it cannot serve (503), could not be reached, or sent nothing usable.
  - **field `serialVersionUID`** of type `long`, starting as `1L`
    - Declaration: `private static final long serialVersionUID = 1L`
  - **method `ProviderUnavailable`**: Creates the failure.
    - Signature: `public ProviderUnavailable(String message)`
    - input `message`: what went wrong
    - What the body does, in order:
      - calls the parent constructor: `super(message, null)`
  - **method `ProviderUnavailable`**: Creates the failure with its cause.
    - Signature: `public ProviderUnavailable(String message, Throwable cause)`
    - input `message`: what went wrong
    - input `cause`: the underlying error
    - What the body does, in order:
      - calls the parent constructor: `super(message, cause)`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.


### `price-service/src/main/java/dev/plainround/price/domain/Quote.java`

Package `dev.plainround.price.domain`. A price the provider gave us at a moment in time.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price the provider gave us at a moment in time.
 *
 * @param price the unit price, positive
 * @param fetchedAt when the provider answered; the cache measures a quote's age from here
 */
public record Quote(BigDecimal price, Instant fetchedAt) {

    /** Checks the invariants. */
    public Quote {
        if (price == null || price.signum() <= 0) {
            throw new ProviderUnavailable("the provider sent no usable price");
        }
    }
}
```

Reading it, one piece at a time:

- **`Quote`**, a record carrying `BigDecimal price, Instant fetchedAt`. A price the provider gave us at a moment in time.
  - **compact constructor (runs when the record is built, before the fields are set) `Quote`**: Checks the invariants.
    - Signature: `public Quote`
    - What the body does, in order:
      - when `price == null || price.signum() <= 0` is true:
        - throws a new `ProviderUnavailable` created with `"the provider sent no usable price"`

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **public**: `public` lets any class use the member.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.


### `price-service/src/main/java/dev/plainround/price/domain/QuoteCache.java`

Package `dev.plainround.price.domain`. Port: the last good quote per SKU, remembered so it can be served, labeled stale, while the provider is down.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

import java.util.Optional;

/**
 * Port: the last good quote per SKU, remembered so it can be served, labeled stale, while the
 * provider is down.
 */
public interface QuoteCache {

    /**
     * Remembers a quote, replacing the previous one for the SKU.
     *
     * @param sku the product code
     * @param quote the quote to remember
     */
    void put(String sku, Quote quote);

    /**
     * Returns the remembered quote if it is still young enough to serve.
     *
     * @param sku the product code
     * @return the quote, or empty if there is none or it has expired
     */
    Optional<Quote> get(String sku);
}
```

Reading it, one piece at a time:

- **`QuoteCache`**, a interface. Port: the last good quote per SKU, remembered so it can be served, labeled stale, while the provider is down.
  - **method `put`**: Remembers a quote, replacing the previous one for the SKU.
    - Signature: `void put(String sku, Quote quote)`
    - input `sku`: the product code
    - input `quote`: the quote to remember
  - **method `get`**: Returns the remembered quote if it is still young enough to serve.
    - Signature: `Optional<Quote> get(String sku)`
    - input `sku`: the product code
    - gives back: the quote, or empty if there is none or it has expired

Language features in this file, explained:

- **interface**: An `interface` lists method signatures without bodies. Any class that says it `implements` the interface must provide those methods, so callers can depend on the list alone.
- **public**: `public` lets any class use the member.
- **Optional**: `Optional<T>` is a box that holds either one `T` or nothing. It makes "may be missing" visible in the type instead of using null.


### `price-service/src/main/java/dev/plainround/price/domain/QuoteSource.java`

Package `dev.plainround.price.domain`. Port: where fresh quotes come from.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/** Port: where fresh quotes come from. */
public interface QuoteSource {

    /**
     * Asks the provider for a price.
     *
     * @param sku the product code
     * @return the quote
     * @throws ProviderFailure if the provider cannot answer
     */
    Quote fetch(String sku);
}
```

Reading it, one piece at a time:

- **`QuoteSource`**, a interface. Port: where fresh quotes come from.
  - **method `fetch`**: Asks the provider for a price.
    - Signature: `Quote fetch(String sku)`
    - input `sku`: the product code
    - gives back: the quote
    - throws `ProviderFailure`: if the provider cannot answer

Language features in this file, explained:

- **interface**: An `interface` lists method signatures without bodies. Any class that says it `implements` the interface must provide those methods, so callers can depend on the list alone.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/domain/SkuNotFound.java`

Package `dev.plainround.price.domain`. The catalog has no product with the requested code.

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

/** The catalog has no product with the requested code. */
public class SkuNotFound extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param sku the code that matched nothing
     */
    public SkuNotFound(String sku) {
        super("no product with sku " + sku);
    }
}
```

Reading it, one piece at a time:

- **`SkuNotFound`**, a class that extends `RuntimeException`. The catalog has no product with the requested code.
  - **field `serialVersionUID`** of type `long`, starting as `1L`
    - Declaration: `private static final long serialVersionUID = 1L`
  - **method `SkuNotFound`**: Creates the error.
    - Signature: `public SkuNotFound(String sku)`
    - input `sku`: the code that matched nothing
    - What the body does, in order:
      - calls the parent constructor: `super("no product with sku " + sku)`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.


### `price-service/src/main/java/dev/plainround/price/infrastructure/CacheProperties.java`

Package `dev.plainround.price.infrastructure`. How long a good quote may be served stale, and how many are remembered.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long a good quote may be served stale, and how many are remembered.
 *
 * @param ttl the longest a quote may be served after the provider gave it
 * @param maxEntries the most SKUs remembered; the oldest entry goes first
 */
@Validated
@ConfigurationProperties("price.cache")
public record CacheProperties(
        @DefaultValue("60s") Duration ttl,
        @DefaultValue("10000") @Min(1) @Max(1_000_000) int maxEntries) {}
```

Reading it, one piece at a time:

- **`CacheProperties`**, a record carrying `@DefaultValue("60s") Duration ttl, @DefaultValue("10000") @Min(1) @Max(1_000_000) int maxEntries`. How long a good quote may be served stale, and how many are remembered.
  - annotation `@Validated`: tells Spring to apply validation annotations on the parameters of this class's methods
  - annotation `@ConfigurationProperties("price.cache")`: binds the configuration keys under the given prefix onto the fields of this class

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Validated`: tells Spring to apply validation annotations on the parameters of this class's methods.
- `@ConfigurationProperties`: binds the configuration keys under the given prefix onto the fields of this class.
- `@DefaultValue`: gives the value to use when the configuration key is absent.
- `@Min`: Bean Validation: the number must be at least the given value.
- `@Max`: Bean Validation: the number must be at most the given value.

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/infrastructure/ClockConfig.java`

Package `dev.plainround.price.infrastructure`. The one place the real clock is created; tests replace this bean.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one place the real clock is created; tests replace this bean. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Provides the system clock in UTC.
     *
     * @return the clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

Reading it, one piece at a time:

- **`ClockConfig`**, a class. The one place the real clock is created; tests replace this bean.
  - annotation `@Configuration(proxyBeanMethods = false)`: marks a class whose `@Bean` methods define objects for Spring to manage
  - **method `clock`**: Provides the system clock in UTC.
    - Signature: `public Clock clock()`
    - gives back: the clock
    - annotation `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object
    - What the body does, in order:
      - returns `Clock.systemUTC()`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Configuration`: marks a class whose `@Bean` methods define objects for Spring to manage.
- `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object.

Language features in this file, explained:

- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/infrastructure/HttpQuoteSource.java`

Package `dev.plainround.price.infrastructure`. Adapter: the quote port over the protected provider call.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.ProviderFailure;
import dev.plainround.price.domain.Quote;
import dev.plainround.price.domain.QuoteSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter: the quote port over the protected provider call. It sits outside the retry and the
 * concurrency limit, so what it sees is the final outcome of a logical call, and it names that
 * outcome for the domain.
 */
@Component
@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)
public class HttpQuoteSource implements QuoteSource {

    private final ProviderCall call;
    private final MeterRegistry meters;

    /**
     * Wires the adapter.
     *
     * @param call the protected attempt
     * @param meters where failure counts go
     */
    public HttpQuoteSource(ProviderCall call, MeterRegistry meters) {
        this.call = call;
        this.meters = meters;
    }

    @Override
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
}
```

Reading it, one piece at a time:

- **`HttpQuoteSource`**, a class that implements `QuoteSource`. Adapter: the quote port over the protected provider call. It sits outside the retry and the concurrency limit, so what it sees is the final outcome of a logical call, and it names that outcome for the domain.
  - annotation `@Component`: tells Spring to create one shared instance of this class at startup and manage it
  - annotation `@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)`: creates this bean only when the named configuration key has the stated value
  - **field `call`** of type `ProviderCall`, set once when the object is built.
    - Declaration: `private final ProviderCall call`
  - **field `meters`** of type `MeterRegistry`, set once when the object is built.
    - Declaration: `private final MeterRegistry meters`
  - **method `HttpQuoteSource`**: Wires the adapter.
    - Signature: `public HttpQuoteSource(ProviderCall call, MeterRegistry meters)`
    - input `call`: the protected attempt
    - input `meters`: where failure counts go
    - What the body does, in order:
      - stores `call` in the field `call`
      - stores `meters` in the field `meters`
  - **method `fetch`**: the signature is `Quote fetch(String sku)`.
    - Signature: `public Quote fetch(String sku)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - tries this:
        - returns `call.attempt(sku)`
      - if this fails with `RuntimeException exception`:
        - creates the variable `failure` (of type `ProviderFailure`) holding `ProviderErrors.translate(exception)`
        - calls `meters.counter("price.provider.failures", "reason", ProviderErrors.reason(failure)) .increment()`
        - throws `failure`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Component`: tells Spring to create one shared instance of this class at startup and manage it.
- `@ConditionalOnProperty`: creates this bean only when the named configuration key has the stated value.
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **implements**: `implements X` promises that the class provides every method of the interface `X`.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.


### `price-service/src/main/java/dev/plainround/price/infrastructure/InMemoryCatalog.java`

Package `dev.plainround.price.infrastructure`. Ten fixed products, `sku-0` to `sku-9`: the catalog the shop's other endpoints serve.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.Catalog;
import dev.plainround.price.domain.CatalogEntry;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Ten fixed products, {@code sku-0} to {@code sku-9}: the catalog the shop's other endpoints serve.
 */
@Component
public class InMemoryCatalog implements Catalog {

    private final List<CatalogEntry> entries =
            IntStream.range(0, 10)
                    .mapToObj(index -> new CatalogEntry("sku-" + index, "Item " + index))
                    .toList();

    @Override
    public List<CatalogEntry> all() {
        return entries;
    }

    @Override
    public Optional<CatalogEntry> find(String sku) {
        return entries.stream().filter(entry -> entry.sku().equals(sku)).findFirst();
    }
}
```

Reading it, one piece at a time:

- **`InMemoryCatalog`**, a class that implements `Catalog`. Ten fixed products, `sku-0` to `sku-9`: the catalog the shop's other endpoints serve.
  - annotation `@Component`: tells Spring to create one shared instance of this class at startup and manage it
  - **field `entries`** of type `List<CatalogEntry>`, starting as `IntStream.range(0, 10) .mapToObj(index -> new CatalogEntry("sku-" + index, "Item " + index)) .toList()`
    - Declaration: `private final List<CatalogEntry> entries = IntStream.range(0, 10) .mapToObj(index -> new CatalogEntry("sku-" + index, "Item " + index)) .toList()`
  - **method `all`**: the signature is `List<CatalogEntry> all()`.
    - Signature: `public List<CatalogEntry> all()`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - returns `entries`
  - **method `find`**: the signature is `Optional<CatalogEntry> find(String sku)`.
    - Signature: `public Optional<CatalogEntry> find(String sku)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - returns `entries.stream().filter(entry -> entry.sku().equals(sku)).findFirst()`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Component`: tells Spring to create one shared instance of this class at startup and manage it.
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **implements**: `implements X` promises that the class provides every method of the interface `X`.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **Optional**: `Optional<T>` is a box that holds either one `T` or nothing. It makes "may be missing" visible in the type instead of using null.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **Stream**: A stream processes a collection step by step: `map` converts each item, `filter` keeps some, `toList` collects the result.


### `price-service/src/main/java/dev/plainround/price/infrastructure/InMemoryQuoteCache.java`

Package `dev.plainround.price.infrastructure`. Remembers the last good quote per SKU in memory.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.Quote;
import dev.plainround.price.domain.QuoteCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Remembers the last good quote per SKU in memory. A quote expires {@code ttl} after the provider
 * gave it, measured from the write, never from the last read: a quote that is read often must still
 * age out. The map is bounded and drops the oldest entry first, so a stream of new SKUs cannot grow
 * it without limit.
 */
@Component
@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)
public class InMemoryQuoteCache implements QuoteCache {

    private final Clock clock;
    private final Duration ttl;
    private final MeterRegistry meters;
    private final Map<String, Quote> quotes;

    /**
     * Wires the cache.
     *
     * @param clock the time source
     * @param properties the expiry and the size bound
     * @param meters where hit and miss counts go
     */
    public InMemoryQuoteCache(Clock clock, CacheProperties properties, MeterRegistry meters) {
        this.clock = clock;
        this.ttl = properties.ttl();
        this.meters = meters;
        int maxEntries = properties.maxEntries();
        this.quotes =
                new LinkedHashMap<>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Quote> eldest) {
                        return size() > maxEntries;
                    }
                };
    }

    @Override
    public synchronized void put(String sku, Quote quote) {
        quotes.remove(sku);
        quotes.put(sku, quote);
    }

    @Override
    public synchronized Optional<Quote> get(String sku) {
        Optional<Quote> young = Optional.ofNullable(quotes.get(sku)).filter(this::isYoungEnough);
        if (young.isEmpty()) {
            quotes.remove(sku);
        }
        meters.counter("price.cache.lookups", "result", young.isPresent() ? "hit" : "miss")
                .increment();
        return young;
    }

    private boolean isYoungEnough(Quote quote) {
        return !quote.fetchedAt().plus(ttl).isBefore(clock.instant());
    }
}
```

Reading it, one piece at a time:

- **`InMemoryQuoteCache`**, a class that implements `QuoteCache`. Remembers the last good quote per SKU in memory. A quote expires `ttl` after the provider gave it, measured from the write, never from the last read: a quote that is read often must still age out. The map is bounded and drops the oldest entry first, so a stream of new SKUs cannot grow it without limit.
  - annotation `@Component`: tells Spring to create one shared instance of this class at startup and manage it
  - annotation `@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)`: creates this bean only when the named configuration key has the stated value
  - **field `clock`** of type `Clock`, set once when the object is built.
    - Declaration: `private final Clock clock`
  - **field `ttl`** of type `Duration`, set once when the object is built.
    - Declaration: `private final Duration ttl`
  - **field `meters`** of type `MeterRegistry`, set once when the object is built.
    - Declaration: `private final MeterRegistry meters`
  - **field `quotes`** of type `Quote>`, set once when the object is built.
    - Declaration: `private final Map<String, Quote> quotes`
  - **method `InMemoryQuoteCache`**: Wires the cache.
    - Signature: `public InMemoryQuoteCache(Clock clock, CacheProperties properties, MeterRegistry meters)`
    - input `clock`: the time source
    - input `properties`: the expiry and the size bound
    - input `meters`: where hit and miss counts go
    - What the body does, in order:
      - stores `clock` in the field `clock`
      - stores `properties.ttl()` in the field `ttl`
      - stores `meters` in the field `meters`
      - creates the variable `maxEntries` (of type `int`) holding `properties.maxEntries()`
      - stores `new LinkedHashMap<>() { private static final long serialVersionUID = 1L; @Override protected boolean removeEldestEntry(Map.Entry<String, Quote> eldest) { return size() > maxEntries; } }` in the field `quotes`
  - **method `put`**: the signature is `synchronized void put(String sku, Quote quote)`.
    - Signature: `public synchronized void put(String sku, Quote quote)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - calls `quotes.remove(sku)`
      - calls `quotes.put(sku, quote)`
  - **method `get`**: the signature is `synchronized Optional<Quote> get(String sku)`.
    - Signature: `public synchronized Optional<Quote> get(String sku)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - creates the variable `young` (of type `Optional<Quote>`) holding `Optional.ofNullable(quotes.get(sku)).filter(this::isYoungEnough)`
      - when `young.isEmpty()` is true:
        - calls `quotes.remove(sku)`
      - calls `meters.counter("price.cache.lookups", "result", young.isPresent() ? "hit" : "miss") .increment()`
      - returns `young`
  - **method `isYoungEnough`**: the signature is `boolean isYoungEnough(Quote quote)`.
    - Signature: `private boolean isYoungEnough(Quote quote)`
    - What the body does, in order:
      - returns `!quote.fetchedAt().plus(ttl).isBefore(clock.instant())`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Component`: tells Spring to create one shared instance of this class at startup and manage it.
- `@ConditionalOnProperty`: creates this bean only when the named configuration key has the stated value.
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **implements**: `implements X` promises that the class provides every method of the interface `X`.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **Optional**: `Optional<T>` is a box that holds either one `T` or nothing. It makes "may be missing" visible in the type instead of using null.
- **Map**: A `Map` stores values under keys, like a dictionary.


### `price-service/src/main/java/dev/plainround/price/infrastructure/NoQuoteCache.java`

Package `dev.plainround.price.infrastructure`. Remembers nothing: the "before" run of the benchmark (`price.protection=none`) has no cache.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.Quote;
import dev.plainround.price.domain.QuoteCache;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Remembers nothing: the "before" run of the benchmark ({@code price.protection=none}) has no
 * cache.
 */
@Component
@ConditionalOnProperty(name = "price.protection", havingValue = "none")
public class NoQuoteCache implements QuoteCache {

    @Override
    public void put(String sku, Quote quote) {
        // Deliberately empty: the unprotected variant keeps no quotes.
    }

    @Override
    public Optional<Quote> get(String sku) {
        return Optional.empty();
    }
}
```

Reading it, one piece at a time:

- **`NoQuoteCache`**, a class that implements `QuoteCache`. Remembers nothing: the "before" run of the benchmark (`price.protection=none`) has no cache.
  - annotation `@Component`: tells Spring to create one shared instance of this class at startup and manage it
  - annotation `@ConditionalOnProperty(name = "price.protection", havingValue = "none")`: creates this bean only when the named configuration key has the stated value
  - **method `put`**: the signature is `void put(String sku, Quote quote)`.
    - Signature: `public void put(String sku, Quote quote)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
  - **method `get`**: the signature is `Optional<Quote> get(String sku)`.
    - Signature: `public Optional<Quote> get(String sku)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - returns `Optional.empty()`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Component`: tells Spring to create one shared instance of this class at startup and manage it.
- `@ConditionalOnProperty`: creates this bean only when the named configuration key has the stated value.
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **public**: `public` lets any class use the member.
- **implements**: `implements X` promises that the class provides every method of the interface `X`.
- **Optional**: `Optional<T>` is a box that holds either one `T` or nothing. It makes "may be missing" visible in the type instead of using null.


### `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderCall.java`

Package `dev.plainround.price.infrastructure`. One protected attempt to ask the provider for a price.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.ProviderUnavailable;
import dev.plainround.price.domain.Quote;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * One protected attempt to ask the provider for a price. It is its own bean, so the proxy that
 * applies the annotations is always in the path: a call from a method of the same class would skip
 * it.
 *
 * <p>Two rules live here, both from the lesson:
 *
 * <ul>
 *   <li>{@code @Retryable} retries a 503 only, at most twice, with growing, jittered delays. A
 *       timeout is a different exception type and is never retried: retrying a call that is already
 *       slow adds load.
 *   <li>{@code @ConcurrencyLimit} with {@code REJECT} lets at most N calls be in flight and refuses
 *       the rest at once. Waiting in a queue would make latency unbounded while the provider hangs.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)
public class ProviderCall {

    private final RestClient restClient;
    private final MeterRegistry meters;
    private final Clock clock;

    /**
     * Wires the call.
     *
     * @param restClient the client with timeouts
     * @param meters where attempt counts and timings go
     * @param clock the time source for the quote's timestamp
     */
    public ProviderCall(RestClient restClient, MeterRegistry meters, Clock clock) {
        this.restClient = restClient;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Makes one logical call: up to three attempts on 503, each limited by the concurrency cap.
     *
     * @param sku the product code
     * @return the quote
     */
    @Retryable(
            includes = HttpServerErrorException.ServiceUnavailable.class,
            maxRetriesString = "${price.provider.retry.max-retries:2}",
            delayString = "${price.provider.retry.delay-ms:100}",
            multiplierString = "${price.provider.retry.multiplier:2.0}",
            jitterString = "${price.provider.retry.jitter-ms:50}",
            maxDelayString = "${price.provider.retry.max-delay-ms:1000}")
    @ConcurrencyLimit(
            limitString = "${price.provider.max-concurrent-calls:10}",
            policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
    public Quote attempt(String sku) {
        Timer.Sample sample = Timer.start(meters);
        String result = "error";
        try {
            Quote quote =
                    quoteOf(
                            restClient
                                    .get()
                                    .uri("/quotes/{sku}", sku)
                                    .retrieve()
                                    .body(QuoteBody.class));
            result = "success";
            return quote;
        } catch (HttpServerErrorException.ServiceUnavailable unavailable) {
            result = "unavailable";
            throw unavailable;
        } catch (ResourceAccessException ioProblem) {
            result = "io";
            throw ioProblem;
        } finally {
            record(sample, result);
        }
    }

    private Quote quoteOf(QuoteBody body) {
        if (body == null || body.price() == null) {
            throw new ProviderUnavailable("the provider sent no price");
        }
        return new Quote(body.price(), clock.instant());
    }

    private void record(Timer.Sample sample, String result) {
        meters.counter("price.provider.attempts", "result", result).increment();
        sample.stop(
                Timer.builder("price.provider.call")
                        .tag("result", result)
                        .publishPercentileHistogram()
                        .register(meters));
    }

    /**
     * The provider's JSON answer.
     *
     * @param price the unit price
     */
    public record QuoteBody(BigDecimal price) {}
}
```

Reading it, one piece at a time:

- **`ProviderCall`**, a class. One protected attempt to ask the provider for a price. It is its own bean, so the proxy that applies the annotations is always in the path: a call from a method of the same class would skip it. Two rules live here, both from the lesson: `@Retryable` retries a 503 only, at most twice, with growing, jittered delays. A timeout is a different exception type and is never retried: retrying a call that is already slow adds load. `@ConcurrencyLimit` with `REJECT` lets at most N calls be in flight and refuses the rest at once. Waiting in a queue would make latency unbounded while the provider hangs.
  - annotation `@Component`: tells Spring to create one shared instance of this class at startup and manage it
  - annotation `@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)`: creates this bean only when the named configuration key has the stated value
  - **field `restClient`** of type `RestClient`, set once when the object is built.
    - Declaration: `private final RestClient restClient`
  - **field `meters`** of type `MeterRegistry`, set once when the object is built.
    - Declaration: `private final MeterRegistry meters`
  - **field `clock`** of type `Clock`, set once when the object is built.
    - Declaration: `private final Clock clock`
  - **method `ProviderCall`**: Wires the call.
    - Signature: `public ProviderCall(RestClient restClient, MeterRegistry meters, Clock clock)`
    - input `restClient`: the client with timeouts
    - input `meters`: where attempt counts and timings go
    - input `clock`: the time source for the quote's timestamp
    - What the body does, in order:
      - stores `restClient` in the field `restClient`
      - stores `meters` in the field `meters`
      - stores `clock` in the field `clock`
  - **method `attempt`**: Makes one logical call: up to three attempts on 503, each limited by the concurrency cap.
    - Signature: `public Quote attempt(String sku)`
    - input `sku`: the product code
    - gives back: the quote
    - annotation `@Retryable( includes = HttpServerErrorException.ServiceUnavailable.class, maxRetriesString = "${price.provider.retry.max-retries:2}", delayString = "${price.provider.retry.delay-ms:100}", multiplierString = "${price.provider.retry.multiplier:2.0}", jitterString = "${price.provider.retry.jitter-ms:50}", maxDelayString = "${price.provider.retry.max-delay-ms:1000}")`: asks Spring Framework 7's resilience support to call this method again when it fails with the listed exceptions
    - annotation `@ConcurrencyLimit( limitString = "${price.provider.max-concurrent-calls:10}", policy = ConcurrencyLimit.ThrottlePolicy.REJECT)`: caps how many threads may be inside this method at once; extra callers wait or are rejected as configured
    - What the body does, in order:
      - creates the variable `sample` (of type `Timer.Sample`) holding `Timer.start(meters)`
      - creates the variable `result` (of type `String`) holding `"error"`
      - tries this:
        - creates the variable `quote` (of type `Quote`) holding `quoteOf( restClient .get() .uri("/quotes/{sku}", sku) .retrieve() .body(QuoteBody.class))`
        - sets `result` to `"success"`
        - returns `quote`
      - if this fails with `HttpServerErrorException.ServiceUnavailable unavailable`:
        - sets `result` to `"unavailable"`
        - throws `unavailable`
      - if this fails with `ResourceAccessException ioProblem`:
        - sets `result` to `"io"`
        - throws `ioProblem`
      - whatever happened above, finally:
        - calls `record(sample, result)`
  - **method `quoteOf`**: the signature is `Quote quoteOf(QuoteBody body)`.
    - Signature: `private Quote quoteOf(QuoteBody body)`
    - What the body does, in order:
      - when `body == null || body.price() == null` is true:
        - throws a new `ProviderUnavailable` created with `"the provider sent no price"`
      - returns `new Quote(body.price(), clock.instant())`
  - **method `record`**: the signature is `void record(Timer.Sample sample, String result)`.
    - Signature: `private void record(Timer.Sample sample, String result)`
    - What the body does, in order:
      - calls `meters.counter("price.provider.attempts", "result", result).increment()`
      - calls `sample.stop( Timer.builder("price.provider.call") .tag("result", result) .publishPercentileHistogram() .register(meters))`
  - **`QuoteBody`**, a record carrying `BigDecimal price`. The provider's JSON answer.

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Component`: tells Spring to create one shared instance of this class at startup and manage it.
- `@ConditionalOnProperty`: creates this bean only when the named configuration key has the stated value.
- `@Retryable`: asks Spring Framework 7's resilience support to call this method again when it fails with the listed exceptions.
- `@ConcurrencyLimit`: caps how many threads may be inside this method at once; extra callers wait or are rejected as configured.

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.


### `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderClientConfig.java`

Package `dev.plainround.price.infrastructure`. Builds the RestClient for the provider, with a connect timeout and a read timeout on every call.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the RestClient for the provider, with a connect timeout and a read timeout on every call.
 */
@Configuration(proxyBeanMethods = false)
public class ProviderClientConfig {

    /**
     * Creates the RestClient used for provider calls. It starts from Spring Boot's builder so
     * metrics and traces cover every call, and sets both timeouts explicitly.
     *
     * @param builder Spring Boot's RestClient builder, with observation and message converters
     * @param properties the provider address and timeouts
     * @return the client
     */
    @Bean
    @ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)
    public RestClient providerRestClient(
            RestClient.Builder builder, ProviderProperties properties) {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    /**
     * Creates the unprotected client of the benchmark's "before" run: no connect timeout, no read
     * timeout.
     *
     * @param builder Spring Boot's RestClient builder
     * @param properties the provider address
     * @return the client
     */
    @Bean("providerRestClient")
    @ConditionalOnProperty(name = "price.protection", havingValue = "none")
    public RestClient unprotectedRestClient(
            RestClient.Builder builder, ProviderProperties properties) {
        return builder.baseUrl(properties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }
}
```

Reading it, one piece at a time:

- **`ProviderClientConfig`**, a class. Builds the RestClient for the provider, with a connect timeout and a read timeout on every call.
  - annotation `@Configuration(proxyBeanMethods = false)`: marks a class whose `@Bean` methods define objects for Spring to manage
  - **method `providerRestClient`**: Creates the RestClient used for provider calls. It starts from Spring Boot's builder so metrics and traces cover every call, and sets both timeouts explicitly.
    - Signature: `public RestClient providerRestClient( RestClient.Builder builder, ProviderProperties properties)`
    - input `builder`: Spring Boot's RestClient builder, with observation and message converters
    - input `properties`: the provider address and timeouts
    - gives back: the client
    - annotation `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object
    - annotation `@ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)`: creates this bean only when the named configuration key has the stated value
    - What the body does, in order:
      - creates the variable `httpClient` (of type `HttpClient`) holding `HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build()`
      - creates the variable `factory` (of type `JdkClientHttpRequestFactory`) holding `new JdkClientHttpRequestFactory(httpClient)`
      - calls `factory.setReadTimeout(properties.readTimeout())`
      - returns `builder.baseUrl(properties.baseUrl()).requestFactory(factory).build()`
  - **method `unprotectedRestClient`**: Creates the unprotected client of the benchmark's "before" run: no connect timeout, no read timeout.
    - Signature: `public RestClient unprotectedRestClient( RestClient.Builder builder, ProviderProperties properties)`
    - input `builder`: Spring Boot's RestClient builder
    - input `properties`: the provider address
    - gives back: the client
    - annotation `@Bean("providerRestClient")`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object
    - annotation `@ConditionalOnProperty(name = "price.protection", havingValue = "none")`: creates this bean only when the named configuration key has the stated value
    - What the body does, in order:
      - returns `builder.baseUrl(properties.baseUrl()) .requestFactory(new JdkClientHttpRequestFactory()) .build()`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Configuration`: marks a class whose `@Bean` methods define objects for Spring to manage.
- `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object.
- `@ConditionalOnProperty`: creates this bean only when the named configuration key has the stated value.

Language features in this file, explained:

- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderErrors.java`

Package `dev.plainround.price.infrastructure`. Turns what the HTTP client and the resilience proxies throw into the domain's three named provider failures.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.ProviderBusy;
import dev.plainround.price.domain.ProviderFailure;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.ProviderUnavailable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns what the HTTP client and the resilience proxies throw into the domain's three named
 * provider failures.
 */
final class ProviderErrors {

    private ProviderErrors() {}

    /**
     * Names a failure of a provider call.
     *
     * @param exception what the call threw
     * @return the domain failure, or the exception itself when it already is one
     */
    static ProviderFailure translate(RuntimeException exception) {
        return switch (exception) {
            case ProviderFailure failure -> failure;
            case InvocationRejectedException rejected -> new ProviderBusy(rejected);
            case ResourceAccessException ioProblem when isTimeout(ioProblem) ->
                    new ProviderTimeout(ioProblem);
            case ResourceAccessException ioProblem ->
                    new ProviderUnavailable("the provider could not be reached", ioProblem);
            case HttpServerErrorException serverError ->
                    new ProviderUnavailable(
                            "the provider answered " + serverError.getStatusCode().value(),
                            serverError);
            default -> new ProviderUnavailable("unexpected answer from the provider", exception);
        };
    }

    /**
     * Gives a failure a short name for metrics.
     *
     * @param failure the failure
     * @return {@code timeout}, {@code busy} or {@code unavailable}
     */
    static String reason(ProviderFailure failure) {
        return switch (failure) {
            case ProviderTimeout timeout -> "timeout";
            case ProviderBusy busy -> "busy";
            case ProviderUnavailable unavailable -> "unavailable";
        };
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause instanceof IOException
                    && String.valueOf(cause.getMessage()).contains("timed out")) {
                return true;
            }
        }
        return false;
    }
}
```

Reading it, one piece at a time:

- **`ProviderErrors`**, a class. Turns what the HTTP client and the resilience proxies throw into the domain's three named provider failures.
  - **method `ProviderErrors`**: the signature is `ProviderErrors()`.
    - Signature: `private ProviderErrors()`
  - **method `translate`**: Names a failure of a provider call.
    - Signature: `static ProviderFailure translate(RuntimeException exception)`
    - input `exception`: what the call threw
    - gives back: the domain failure, or the exception itself when it already is one
    - What the body does, in order:
      - returns `switch (exception) { case ProviderFailure failure -> failure; case InvocationRejectedException rejected -> new ProviderBusy(rejected); case ResourceAccessException ioProblem when isTimeout(ioProblem) -> new ProviderTimeout(ioProblem); case ResourceAccessException ioProblem -> new ProviderUnavailable("the provider could not be reached", ioProblem); case HttpServerErrorException serverError -> new ProviderUnavailable( "the provider answered " + serverError.getStatusCode().value(), serverError); default -> new ProviderUnavailable("unexpected answer from the provider", exception); }`
  - **method `reason`**: Gives a failure a short name for metrics.
    - Signature: `static String reason(ProviderFailure failure)`
    - input `failure`: the failure
    - gives back: `timeout`, `busy` or `unavailable`
    - What the body does, in order:
      - returns `switch (failure) { case ProviderTimeout timeout -> "timeout"; case ProviderBusy busy -> "busy"; case ProviderUnavailable unavailable -> "unavailable"; }`
  - **method `isTimeout`**: the signature is `static boolean isTimeout(Throwable exception)`.
    - Signature: `private static boolean isTimeout(Throwable exception)`
    - What the body does, in order:
      - repeats for `Throwable cause = exception; cause != null; cause = cause.getCause()`:
        - when `cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException` is true:
          - returns `true`
        - when `cause instanceof IOException && String.valueOf(cause.getMessage()).contains("timed out")` is true:
          - returns `true`
      - returns `false`

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **switch**: `switch` chooses one branch by the value of an expression.


### `price-service/src/main/java/dev/plainround/price/infrastructure/ProviderProperties.java`

Package `dev.plainround.price.infrastructure`. Where the provider is and how long a call may take.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the provider is and how long a call may take. Both timeouts are mandatory and positive:
 * there is no way to configure a call that waits forever.
 *
 * @param baseUrl the provider's base URL
 * @param connectTimeout how long opening the connection may take
 * @param readTimeout how long waiting for the answer may take once connected
 */
@Validated
@ConfigurationProperties("price.provider")
public record ProviderProperties(
        @NotBlank String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout) {

    /**
     * Checks that both timeouts are positive.
     *
     * @return true when neither timeout is zero or negative
     */
    @AssertTrue(message = "connect-timeout and read-timeout must be positive")
    public boolean isTimeoutsPositive() {
        return connectTimeout.isPositive() && readTimeout.isPositive();
    }
}
```

Reading it, one piece at a time:

- **`ProviderProperties`**, a record carrying `@NotBlank String baseUrl, @DefaultValue("1s") Duration connectTimeout, @DefaultValue("2s") Duration readTimeout`. Where the provider is and how long a call may take. Both timeouts are mandatory and positive: there is no way to configure a call that waits forever.
  - annotation `@Validated`: tells Spring to apply validation annotations on the parameters of this class's methods
  - annotation `@ConfigurationProperties("price.provider")`: binds the configuration keys under the given prefix onto the fields of this class
  - **method `isTimeoutsPositive`**: Checks that both timeouts are positive.
    - Signature: `public boolean isTimeoutsPositive()`
    - gives back: true when neither timeout is zero or negative
    - annotation `@AssertTrue(message = "connect-timeout and read-timeout must be positive")`: Bean Validation: this boolean method must return true, which lets a rule involving several fields be checked
    - What the body does, in order:
      - returns `connectTimeout.isPositive() && readTimeout.isPositive()`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Validated`: tells Spring to apply validation annotations on the parameters of this class's methods.
- `@ConfigurationProperties`: binds the configuration keys under the given prefix onto the fields of this class.
- `@AssertTrue`: Bean Validation: this boolean method must return true, which lets a rule involving several fields be checked.
- `@NotBlank`: Bean Validation: the text must not be null and must contain at least one visible character.
- `@DefaultValue`: gives the value to use when the configuration key is absent.

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/infrastructure/ResilienceConfig.java`

Package `dev.plainround.price.infrastructure`. Switches on Spring Framework's own resilience annotations (`@Retryable`, `@ConcurrencyLimit`), the package `org.springframework.resilience.annotation`, not the older separate Spring Retry library.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Switches on Spring Framework's own resilience annotations ({@code @Retryable},
 * {@code @ConcurrencyLimit}), the package {@code org.springframework.resilience.annotation}, not
 * the older separate Spring Retry library.
 */
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
public class ResilienceConfig {}
```

Reading it, one piece at a time:

- **`ResilienceConfig`**, a class. Switches on Spring Framework's own resilience annotations (`@Retryable`, `@ConcurrencyLimit`), the package `org.springframework.resilience.annotation`, not the older separate Spring Retry library.
  - annotation `@Configuration(proxyBeanMethods = false)`: marks a class whose `@Bean` methods define objects for Spring to manage
  - annotation `@EnableResilientMethods`: switches on the `@Retryable` and `@ConcurrencyLimit` handling in this application

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Configuration`: marks a class whose `@Bean` methods define objects for Spring to manage.
- `@EnableResilientMethods`: switches on the `@Retryable` and `@ConcurrencyLimit` handling in this application.

Language features in this file, explained:

- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/infrastructure/UnprotectedQuoteSource.java`

Package `dev.plainround.price.infrastructure`. The "before" of the lesson's before-and-after table, selected with `price.protection=none`: a plain call with no timeout, no retry, no concurrency limit and no cache.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.Quote;
import dev.plainround.price.domain.QuoteSource;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The "before" of the lesson's before-and-after table, selected with {@code price.protection=none}:
 * a plain call with no timeout, no retry, no concurrency limit and no cache. It exists so the
 * benchmark can show what the protection buys; never run it in production.
 */
@Component
@ConditionalOnProperty(name = "price.protection", havingValue = "none")
public class UnprotectedQuoteSource implements QuoteSource {

    private final RestClient restClient;
    private final Clock clock;

    /**
     * Wires the adapter.
     *
     * @param restClient a client without timeouts
     * @param clock the time source for the quote's timestamp
     */
    public UnprotectedQuoteSource(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public Quote fetch(String sku) {
        try {
            ProviderCall.QuoteBody body =
                    restClient
                            .get()
                            .uri("/quotes/{sku}", sku)
                            .retrieve()
                            .body(ProviderCall.QuoteBody.class);
            BigDecimal price = body == null ? null : body.price();
            return new Quote(price, clock.instant());
        } catch (RuntimeException exception) {
            throw ProviderErrors.translate(exception);
        }
    }
}
```

Reading it, one piece at a time:

- **`UnprotectedQuoteSource`**, a class that implements `QuoteSource`. The "before" of the lesson's before-and-after table, selected with `price.protection=none`: a plain call with no timeout, no retry, no concurrency limit and no cache. It exists so the benchmark can show what the protection buys; never run it in production.
  - annotation `@Component`: tells Spring to create one shared instance of this class at startup and manage it
  - annotation `@ConditionalOnProperty(name = "price.protection", havingValue = "none")`: creates this bean only when the named configuration key has the stated value
  - **field `restClient`** of type `RestClient`, set once when the object is built.
    - Declaration: `private final RestClient restClient`
  - **field `clock`** of type `Clock`, set once when the object is built.
    - Declaration: `private final Clock clock`
  - **method `UnprotectedQuoteSource`**: Wires the adapter.
    - Signature: `public UnprotectedQuoteSource(RestClient restClient, Clock clock)`
    - input `restClient`: a client without timeouts
    - input `clock`: the time source for the quote's timestamp
    - What the body does, in order:
      - stores `restClient` in the field `restClient`
      - stores `clock` in the field `clock`
  - **method `fetch`**: the signature is `Quote fetch(String sku)`.
    - Signature: `public Quote fetch(String sku)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - tries this:
        - creates the variable `body` (of type `ProviderCall.QuoteBody`) holding `restClient .get() .uri("/quotes/{sku}", sku) .retrieve() .body(ProviderCall.QuoteBody.class)`
        - creates the variable `price` (of type `BigDecimal`) holding `body == null ? null : body.price()`
        - returns `new Quote(price, clock.instant())`
      - if this fails with `RuntimeException exception`:
        - throws `ProviderErrors.translate(exception)`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Component`: tells Spring to create one shared instance of this class at startup and manage it.
- `@ConditionalOnProperty`: creates this bean only when the named configuration key has the stated value.
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **implements**: `implements X` promises that the class provides every method of the interface `X`.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.


### `price-service/src/main/java/dev/plainround/price/interfaces/web/CatalogController.java`

Package `dev.plainround.price.interfaces.web`. HTTP face of the catalog: an endpoint that never calls the provider, used to show the provider's sickness does not spread.

The whole file, as it is in the repository:

```java
package dev.plainround.price.interfaces.web;

import dev.plainround.price.application.CatalogService;
import dev.plainround.price.domain.CatalogEntry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP face of the catalog: an endpoint that never calls the provider, used to show the provider's
 * sickness does not spread.
 */
@RestController
@RequestMapping("/skus")
public class CatalogController {

    private final CatalogService service;

    /**
     * Wires the controller.
     *
     * @param service the catalog use case
     */
    public CatalogController(CatalogService service) {
        this.service = service;
    }

    /**
     * Lists the products.
     *
     * @return the entries
     */
    @GetMapping
    public List<CatalogEntry> list() {
        return service.list();
    }

    /**
     * Returns one product.
     *
     * @param sku the product code
     * @return the entry
     */
    @GetMapping("/{sku}")
    public CatalogEntry get(@PathVariable String sku) {
        return service.get(sku);
    }
}
```

Reading it, one piece at a time:

- **`CatalogController`**, a class. HTTP face of the catalog: an endpoint that never calls the provider, used to show the provider's sickness does not spread.
  - annotation `@RestController`: marks a class whose methods answer web requests, with return values written as JSON
  - annotation `@RequestMapping("/skus")`: sets the URL path prefix for every handler method in this class (or the path and verb of one method)
  - **field `service`** of type `CatalogService`, set once when the object is built.
    - Declaration: `private final CatalogService service`
  - **method `CatalogController`**: Wires the controller.
    - Signature: `public CatalogController(CatalogService service)`
    - input `service`: the catalog use case
    - What the body does, in order:
      - stores `service` in the field `service`
  - **method `list`**: Lists the products.
    - Signature: `public List<CatalogEntry> list()`
    - gives back: the entries
    - annotation `@GetMapping`: routes HTTP GET requests for the given path to this method
    - What the body does, in order:
      - returns `service.list()`
  - **method `get`**: Returns one product.
    - Signature: `public CatalogEntry get(@PathVariable String sku)`
    - input `sku`: the product code
    - gives back: the entry
    - annotation `@GetMapping("/{sku}")`: routes HTTP GET requests for the given path to this method
    - What the body does, in order:
      - returns `service.get(sku)`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@RestController`: marks a class whose methods answer web requests, with return values written as JSON.
- `@RequestMapping`: sets the URL path prefix for every handler method in this class (or the path and verb of one method).
- `@GetMapping`: routes HTTP GET requests for the given path to this method.
- `@PathVariable`: fills this parameter from the matching `{name}` part of the URL path.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.


### `price-service/src/main/java/dev/plainround/price/interfaces/web/PriceController.java`

Package `dev.plainround.price.interfaces.web`. HTTP face of pricing.

The whole file, as it is in the repository:

```java
package dev.plainround.price.interfaces.web;

import dev.plainround.price.application.PriceService;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of pricing. */
@RestController
public class PriceController {

    private final PriceService service;

    /**
     * Wires the controller.
     *
     * @param service the pricing use case
     */
    public PriceController(PriceService service) {
        this.service = service;
    }

    /**
     * Prices a SKU, fresh when the provider answers and labeled stale when it does not.
     *
     * @param sku the product code, letters, digits, dot, dash and underscore, up to 64 characters
     * @return the price
     */
    @GetMapping("/price/{sku}")
    public PriceResponse price(
            @PathVariable
                    @Pattern(
                            regexp = "[A-Za-z0-9._-]{1,64}",
                            message =
                                    "sku must be 1 to 64 letters, digits, dots, dashes or"
                                            + " underscores")
                    String sku) {
        return PriceResponse.from(service.price(sku));
    }
}
```

Reading it, one piece at a time:

- **`PriceController`**, a class. HTTP face of pricing.
  - annotation `@RestController`: marks a class whose methods answer web requests, with return values written as JSON
  - **field `service`** of type `PriceService`, set once when the object is built.
    - Declaration: `private final PriceService service`
  - **method `PriceController`**: Wires the controller.
    - Signature: `public PriceController(PriceService service)`
    - input `service`: the pricing use case
    - What the body does, in order:
      - stores `service` in the field `service`
  - **method `price`**: Prices a SKU, fresh when the provider answers and labeled stale when it does not.
    - Signature: `public PriceResponse price( @PathVariable @Pattern( regexp = "[A-Za-z0-9._-]{1,64}", message = "sku must be 1 to 64 letters, digits, dots, dashes or" + " underscores") String sku)`
    - input `sku`: the product code, letters, digits, dot, dash and underscore, up to 64 characters
    - gives back: the price
    - annotation `@GetMapping("/price/{sku}")`: routes HTTP GET requests for the given path to this method
    - What the body does, in order:
      - returns `PriceResponse.from(service.price(sku))`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@RestController`: marks a class whose methods answer web requests, with return values written as JSON.
- `@GetMapping`: routes HTTP GET requests for the given path to this method.
- `@PathVariable`: fills this parameter from the matching `{name}` part of the URL path.
- `@Pattern`: Bean Validation: the text must match the given regular expression.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/interfaces/web/PriceResponse.java`

Package `dev.plainround.price.interfaces.web`. A price as the API shows it.

The whole file, as it is in the repository:

```java
package dev.plainround.price.interfaces.web;

import dev.plainround.price.domain.PriceResult;
import java.math.BigDecimal;

/**
 * A price as the API shows it.
 *
 * @param price the unit price
 * @param stale true when the provider failed and this is its last good answer
 * @param ageSeconds how many seconds ago the provider gave this price, 0 when fresh
 */
public record PriceResponse(BigDecimal price, boolean stale, long ageSeconds) {

    /**
     * Converts a domain result.
     *
     * @param result the result
     * @return its API shape
     */
    public static PriceResponse from(PriceResult result) {
        return new PriceResponse(result.price(), result.stale(), result.ageSeconds());
    }
}
```

Reading it, one piece at a time:

- **`PriceResponse`**, a record carrying `BigDecimal price, boolean stale, long ageSeconds`. A price as the API shows it.
  - **method `from`**: Converts a domain result.
    - Signature: `public static PriceResponse from(PriceResult result)`
    - input `result`: the result
    - gives back: its API shape
    - What the body does, in order:
      - returns `new PriceResponse(result.price(), result.stale(), result.ageSeconds())`

Language features in this file, explained:

- **record**: A `record` is a short way to declare a class that only carries data. The names in the parentheses become fields, a constructor, read methods with the same names, and `equals`, `hashCode` and `toString`.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **public**: `public` lets any class use the member.


### `price-service/src/main/java/dev/plainround/price/interfaces/web/ProviderExceptionHandler.java`

Package `dev.plainround.price.interfaces.web`. The one place errors become `application/problem+json`.

The whole file, as it is in the repository:

```java
package dev.plainround.price.interfaces.web;

import dev.plainround.price.domain.NoPriceAvailable;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.SkuNotFound;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place errors become {@code application/problem+json}. A SKU that could not be priced is a
 * 504 when the provider timed out and a 503 (with {@code Retry-After}) for every other provider
 * failure; the two mean different things to a caller and are never merged.
 */
@RestControllerAdvice
public class ProviderExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderExceptionHandler.class);
    private static final String PROBLEM_URN = "urn:plainround:problem:";

    /**
     * Maps "provider failed and nothing recent is remembered" to 504 or 503.
     *
     * @param exception the error
     * @return the problem
     */
    @ExceptionHandler(NoPriceAvailable.class)
    public ResponseEntity<ProblemDetail> handleNoPrice(NoPriceAvailable exception) {
        if (exception.failure() instanceof ProviderTimeout) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(
                            problem(
                                    HttpStatus.GATEWAY_TIMEOUT,
                                    "provider-timeout",
                                    "Provider timed out",
                                    exception.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(
                        problem(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "provider-unavailable",
                                "Provider unavailable",
                                exception.getMessage()));
    }

    /**
     * Maps an unknown catalog code to 404.
     *
     * @param exception the error
     * @return the problem
     */
    @ExceptionHandler(SkuNotFound.class)
    public ProblemDetail handleSkuNotFound(SkuNotFound exception) {
        return problem(
                HttpStatus.NOT_FOUND, "sku-not-found", "Product not found", exception.getMessage());
    }

    /**
     * Maps anything unexpected to 500 without leaking its message.
     *
     * @param exception the error
     * @return the problem
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        LOG.error("unexpected error", exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "The request failed.");
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<Map<String, String>> errors =
                exception.getParameterValidationResults().stream()
                        .flatMap(result -> errorsOf(result).stream())
                        .toList();
        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "validation-failed",
                        "Validation failed",
                        "The request failed validation.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static List<Map<String, String>> errorsOf(ParameterValidationResult result) {
        String parameter = String.valueOf(result.getMethodParameter().getParameterName());
        return result.getResolvableErrors().stream()
                .map(
                        error ->
                                Map.of(
                                        "field",
                                        parameter,
                                        "message",
                                        String.valueOf(error.getDefaultMessage())))
                .toList();
    }

    private static ProblemDetail problem(
            HttpStatus status, String name, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_URN + name));
        problem.setTitle(title);
        return problem;
    }
}
```

Reading it, one piece at a time:

- **`ProviderExceptionHandler`**, a class that extends `ResponseEntityExceptionHandler`. The one place errors become `application/problem+json`. A SKU that could not be priced is a 504 when the provider timed out and a 503 (with `Retry-After`) for every other provider failure; the two mean different things to a caller and are never merged.
  - annotation `@RestControllerAdvice`: marks a class that is shared by all controllers and turns exceptions into responses
  - **field `LOG`** of type `Logger`, starting as `LoggerFactory.getLogger(ProviderExceptionHandler.class)`
    - Declaration: `private static final Logger LOG = LoggerFactory.getLogger(ProviderExceptionHandler.class)`
  - **field `PROBLEM_URN`** of type `String`, starting as `"urn:plainround:problem:"`
    - Declaration: `private static final String PROBLEM_URN = "urn:plainround:problem:"`
  - **method `handleNoPrice`**: Maps "provider failed and nothing recent is remembered" to 504 or 503.
    - Signature: `public ResponseEntity<ProblemDetail> handleNoPrice(NoPriceAvailable exception)`
    - input `exception`: the error
    - gives back: the problem
    - annotation `@ExceptionHandler(NoPriceAvailable.class)`: says this method handles the named exception type: when a controller throws it, Spring calls this method to build the response
    - What the body does, in order:
      - when `exception.failure() instanceof ProviderTimeout` is true:
        - returns `ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT) .body( problem( HttpStatus.GATEWAY_TIMEOUT, "provider-timeout", "Provider timed out", exception.getMessage()))`
      - returns `ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE) .header(HttpHeaders.RETRY_AFTER, "1") .body( problem( HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable", "Provider unavailable", exception.getMessage()))`
  - **method `handleSkuNotFound`**: Maps an unknown catalog code to 404.
    - Signature: `public ProblemDetail handleSkuNotFound(SkuNotFound exception)`
    - input `exception`: the error
    - gives back: the problem
    - annotation `@ExceptionHandler(SkuNotFound.class)`: says this method handles the named exception type: when a controller throws it, Spring calls this method to build the response
    - What the body does, in order:
      - returns `problem( HttpStatus.NOT_FOUND, "sku-not-found", "Product not found", exception.getMessage())`
  - **method `handleUnexpected`**: Maps anything unexpected to 500 without leaking its message.
    - Signature: `public ProblemDetail handleUnexpected(Exception exception)`
    - input `exception`: the error
    - gives back: the problem
    - annotation `@ExceptionHandler(Exception.class)`: says this method handles the named exception type: when a controller throws it, Spring calls this method to build the response
    - What the body does, in order:
      - calls `LOG.error("unexpected error", exception)`
      - returns `problem( HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error", "The request failed.")`
  - **method `handleHandlerMethodValidationException`**: the signature is `ResponseEntity<Object> handleHandlerMethodValidationException( HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request)`.
    - Signature: `protected ResponseEntity<Object> handleHandlerMethodValidationException( HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - creates the variable `errors` (of type `List<Map<String, String>>`) holding `exception.getParameterValidationResults().stream() .flatMap(result -> errorsOf(result).stream()) .toList()`
      - creates the variable `problem` (of type `ProblemDetail`) holding `problem( HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "The request failed validation.")`
      - calls `problem.setProperty("errors", errors)`
      - returns `ResponseEntity.badRequest().body(problem)`
  - **method `errorsOf`**: the signature is `static List<Map<String, String>> errorsOf(ParameterValidationResult result)`.
    - Signature: `private static List<Map<String, String>> errorsOf(ParameterValidationResult result)`
    - What the body does, in order:
      - creates the variable `parameter` (of type `String`) holding `String.valueOf(result.getMethodParameter().getParameterName())`
      - returns `result.getResolvableErrors().stream() .map( error -> Map.of( "field", parameter, "message", String.valueOf(error.getDefaultMessage()))) .toList()`
  - **method `problem`**: the signature is `static ProblemDetail problem( HttpStatus status, String name, String title, String detail)`.
    - Signature: `private static ProblemDetail problem( HttpStatus status, String name, String title, String detail)`
    - What the body does, in order:
      - creates the variable `problem` (of type `ProblemDetail`) holding `ProblemDetail.forStatusAndDetail(status, detail)`
      - calls `problem.setType(URI.create(PROBLEM_URN + name))`
      - calls `problem.setTitle(title)`
      - returns `problem`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@RestControllerAdvice`: marks a class that is shared by all controllers and turns exceptions into responses.
- `@ExceptionHandler`: says this method handles the named exception type: when a controller throws it, Spring calls this method to build the response.
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **Map**: A `Map` stores values under keys, like a dictionary.
- **Stream**: A stream processes a collection step by step: `map` converts each item, `filter` keeps some, `toList` collects the result.


### `price-service/src/main/java/dev/plainround/price/interfaces/web/TraceIdFilter.java`

Package `dev.plainround.price.interfaces.web`. Puts the id of the request's trace in the `X-Trace-Id` response header, so a caller (or the README's trace capture) can find exactly this request in the trace backend.

The whole file, as it is in the repository:

```java
package dev.plainround.price.interfaces.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the id of the request's trace in the {@code X-Trace-Id} response header, so a caller (or the
 * README's trace capture) can find exactly this request in the trace backend. It runs just inside
 * Spring's observation filter, where the span already exists.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends OncePerRequestFilter {

    /** Name of the response header. */
    public static final String HEADER = "X-Trace-Id";

    private final Tracer tracer;

    /**
     * Wires the filter.
     *
     * @param tracer the tracer that knows the current span
     */
    public TraceIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(HEADER, span.context().traceId());
        }
        chain.doFilter(request, response);
    }
}
```

Reading it, one piece at a time:

- **`TraceIdFilter`**, a class that extends `OncePerRequestFilter`. Puts the id of the request's trace in the `X-Trace-Id` response header, so a caller (or the README's trace capture) can find exactly this request in the trace backend. It runs just inside Spring's observation filter, where the span already exists.
  - annotation `@Component`: tells Spring to create one shared instance of this class at startup and manage it
  - annotation `@Order(Ordered.HIGHEST_PRECEDENCE + 2)`: sets the sequence among several items of the same kind (a smaller number runs first)
  - **field `HEADER`** of type `String`: Name of the response header.
    - Declaration: `public static final String HEADER = "X-Trace-Id"`
  - **field `tracer`** of type `Tracer`, set once when the object is built.
    - Declaration: `private final Tracer tracer`
  - **method `TraceIdFilter`**: Wires the filter.
    - Signature: `public TraceIdFilter(Tracer tracer)`
    - input `tracer`: the tracer that knows the current span
    - What the body does, in order:
      - stores `tracer` in the field `tracer`
  - **method `doFilterInternal`**: the signature is `void doFilterInternal( HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException`.
    - Signature: `protected void doFilterInternal( HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - creates the variable `span` (of type `Span`) holding `tracer.currentSpan()`
      - when `span != null` is true:
        - calls `response.setHeader(HEADER, span.context().traceId())`
      - calls `chain.doFilter(request, response)`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Component`: tells Spring to create one shared instance of this class at startup and manage it.
- `@Order`: sets the sequence among several items of the same kind (a smaller number runs first).
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.


## Try it yourself

1. Open the file that has `main` in it and find the `SpringApplication.run` call. Everything else is reached from there.
2. Pick one class and read its Javadoc (the `/** ... */` comment above it) first. It states the job in one sentence.
3. Run `./mvnw -q compile` and look in `target/classes`: each `.java` file became a `.class` file of the same name.

## Mistakes people make

- Reading the methods in file order instead of following a request. Follow one request from the controller down.
- Thinking an annotation does nothing. Each `@` label above is read by Spring or by a validation library, and removing one changes behavior.
- Mixing layers: a domain class must not import Spring. The architecture test in [ArchUnit](archunit.md) fails the build if it does.

## Check yourself

1. Which folder holds the classes that know nothing about HTTP or databases? *The domain folder.*
2. Who creates the objects the constructors ask for? *Spring, at startup.*
3. What does `final` on a field promise? *It is set once, in the constructor.*

## Further reading

[Java](java.md), [Spring Framework](spring-framework.md), [Spring Boot](spring-boot.md), [Spring Web MVC](spring-web-mvc.md) and [the test code note](java-test-code.md) in this folder.
