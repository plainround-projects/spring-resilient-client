# The test code, class by class

## What it is

A test is a small program that uses the application code and checks that the result is what the author expects. This note prints every test class in the repository (`src/test/java`) exactly as it is, and explains each test method below it.

## Why this repository uses it

The tests are the evidence that the code does what the README says. Each test has a name that reads as a sentence. If a test fails, its name tells you which promise broke.

## Where it appears

Tests live in each module's `src/test/java`: the provider's tests (a real in-process HTTP server), the load generator's tests, and the price service's tests (unit, web-layer, architecture rules and an integration test against the fake provider).

## How it works underneath

1. Maven's Surefire plugin finds classes named `*Test`, starts JUnit, and runs every method marked `@Test`. The Failsafe plugin does the same for `*IT` in the `verify` phase.
2. A test method passes if it returns normally and fails if it throws. An assertion such as `assertThat(x).isEqualTo(y)` throws when the check is false.
3. Tests follow three steps: set up the data, do the thing, check the result. Blank lines in the listings separate the steps.
4. Spring tests start a (small or full) Spring context first, so the test can ask for real beans or send fake HTTP requests.

## Walkthrough of this repository's use

Each class is printed whole, followed by a reading guide. The guide's "reads as" sentence is the test name turned into words, and "What the body does" translates each statement into plain words with the original in backticks.

### `provider/src/test/java/dev/plainround/provider/FakeProviderTest.java`

Package `dev.plainround.provider`. 

The whole file, as it is in the repository:

```java
package dev.plainround.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakeProviderTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private FakeProvider provider;

    @BeforeEach
    void start() throws IOException {
        provider = new FakeProvider(0);
        provider.start();
    }

    @AfterEach
    void stop() {
        provider.close();
    }

    @Test
    void normalModeAnswers200WithADeterministicPriceAndCountsTheCall() throws Exception {
        HttpResponse<String> first = get("/quotes/sku-1");
        HttpResponse<String> second = get("/quotes/sku-1");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).matches("\\{\"price\":\\d+\\.\\d{2}}").isEqualTo(second.body());
        assertThat(provider.callsReceived()).isEqualTo(2);
    }

    @Test
    void fail503ModeAnswers503AtOnce() throws Exception {
        provider.mode(ProviderMode.FAIL503);

        HttpResponse<String> response = get("/quotes/sku-1");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(provider.callsReceived()).isEqualTo(1);
    }

    @Test
    void failNextAnswers503ForExactlyThatManyCallsThenReturnsToTheMode() throws Exception {
        provider.failNext(2);

        assertThat(get("/quotes/sku-1").statusCode()).isEqualTo(503);
        assertThat(get("/quotes/sku-1").statusCode()).isEqualTo(503);
        assertThat(get("/quotes/sku-1").statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/admin/mode?mode=normal&failNext=1").statusCode()).isEqualTo(200);
        assertThat(get("/quotes/sku-1").statusCode()).isEqualTo(503);
        assertThat(get("/quotes/sku-1").statusCode()).isEqualTo(200);
    }

    @Test
    void slowModeAnswersAfterTheConfiguredDelay() throws Exception {
        provider.mode(ProviderMode.SLOW);
        provider.slowMillis(300);
        long started = System.nanoTime();

        HttpResponse<String> response = get("/quotes/sku-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(Duration.ofMillis(290));
    }

    @Test
    void hangModeIsSilentLongerThanAClientWillWaitButStillCountsTheCall() throws Exception {
        provider.mode(ProviderMode.HANG);
        HttpRequest request =
                HttpRequest.newBuilder(uri("/quotes/sku-1")).timeout(Duration.ofSeconds(2)).build();

        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(HttpTimeoutException.class);

        waitUntil(() -> provider.callsReceived() == 1);
    }

    @Test
    void theAdminEndpointSwitchesTheModeAtRunTime() throws Exception {
        HttpResponse<String> changed = send("PUT", "/admin/mode?mode=slow&delayMs=1234");
        HttpResponse<String> stats = get("/admin/stats");

        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(stats.body()).contains("\"mode\":\"slow\"", "\"slowDelayMs\":1234");
    }

    @Test
    void anUnknownModeIs400AndAGetOnTheAdminEndpointIs405() throws Exception {
        assertThat(send("PUT", "/admin/mode?mode=explode").statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/admin/mode").statusCode()).isEqualTo(400);
        assertThat(get("/admin/mode").statusCode()).isEqualTo(405);
    }

    @Test
    void maxInFlightRecordsTheMostCallsServedAtOnce() throws Exception {
        provider.mode(ProviderMode.SLOW);
        provider.slowMillis(300);
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int call = 0; call < 5; call++) {
            calls.add(
                    client.sendAsync(
                            HttpRequest.newBuilder(uri("/quotes/sku-" + call)).build(),
                            HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get();

        assertThat(provider.maxInFlight()).isEqualTo(5);
        assertThat(provider.inFlight()).isZero();
    }

    @Test
    void resetZeroesTheCountersAndMetricsExposeThem() throws Exception {
        get("/quotes/sku-1");

        assertThat(get("/metrics").body())
                .contains("provider_calls_received_total 1", "provider_in_flight_max 1");
        assertThat(send("POST", "/admin/reset").statusCode()).isEqualTo(200);
        assertThat(provider.callsReceived()).isZero();
        assertThat(get("/health").body()).isEqualTo("ok");
    }

    @Test
    void modeNamesParseIgnoringCaseAndRejectNonsense() {
        assertThat(ProviderMode.parse(" Fail503 ")).isEqualTo(ProviderMode.FAIL503);
        assertThatThrownBy(() -> ProviderMode.parse("nope"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderMode.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMainEntryPointStartsAProviderThatAnswersHealth() throws Exception {
        try (FakeProvider started = ProviderMain.start(0)) {
            HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + started.port() + "/health"))
                            .build();

            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).body())
                    .isEqualTo("ok");
        }
    }

    @Test
    void hangLastsAsLongAsConfiguredAndThenAnswersEmptyJson() throws Exception {
        provider.mode(ProviderMode.HANG);
        provider.hangMillis(200);

        HttpResponse<String> response = get("/quotes/sku-1");

        assertThat(response.body()).isEqualTo("{}");
    }

    @Test
    void jsonLogEscapesQuotesAndNewlines() {
        assertThat(JsonLog.escape("a \"b\"\nc\\")).isEqualTo("a \\\"b\\\"\\nc\\\\");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met in 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send("GET", path);
    }

    private HttpResponse<String> send(String method, String path)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(uri(path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + provider.port() + path);
    }
}
```

Reading it, one piece at a time:

- **`FakeProviderTest`**, a class.
  - **field `client`** of type `HttpClient`, starting as `HttpClient.newHttpClient()`
    - Declaration: `private final HttpClient client = HttpClient.newHttpClient()`
  - **field `provider`** of type `FakeProvider`, set once when the object is built.
    - Declaration: `private FakeProvider provider`
  - **method `start`**: the signature is `void start() throws IOException`.
    - Signature: `void start() throws IOException`
    - annotation `@BeforeEach`: runs this method before every test, to prepare a known starting state
    - What the body does, in order:
      - sets `provider` to `new FakeProvider(0)`
      - calls `provider.start()`
  - **method `stop`**: the signature is `void stop()`.
    - Signature: `void stop()`
    - annotation `@AfterEach`: runs this method after every test, to clean up
    - What the body does, in order:
      - calls `provider.close()`
  - **test `normalModeAnswers200WithADeterministicPriceAndCountsTheCall`**: reads as "normal mode answers200 with a deterministic price and counts the call"
    - Signature: `void normalModeAnswers200WithADeterministicPriceAndCountsTheCall() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `first` (of type `HttpResponse<String>`) holding `get("/quotes/sku-1")`
      - creates the variable `second` (of type `HttpResponse<String>`) holding `get("/quotes/sku-1")`
      - checks that `first.statusCode()` equals `200`
      - checks that `first.body()` matches `"\\{\"price\":\\d+\\.\\d{2}}"`
      - and that it equals `second.body()`
      - checks that `provider.callsReceived()` equals `2`
  - **test `fail503ModeAnswers503AtOnce`**: reads as "fail503 mode answers503 at once"
    - Signature: `void fail503ModeAnswers503AtOnce() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.FAIL503)`
      - creates the variable `response` (of type `HttpResponse<String>`) holding `get("/quotes/sku-1")`
      - checks that `response.statusCode()` equals `503`
      - checks that `provider.callsReceived()` equals `1`
  - **test `failNextAnswers503ForExactlyThatManyCallsThenReturnsToTheMode`**: reads as "fail next answers503 for exactly that many calls then returns to the mode"
    - Signature: `void failNextAnswers503ForExactlyThatManyCallsThenReturnsToTheMode() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.failNext(2)`
      - checks that `get("/quotes/sku-1").statusCode()` equals `503`
      - checks that `get("/quotes/sku-1").statusCode()` equals `503`
      - checks that `get("/quotes/sku-1").statusCode()` equals `200`
      - checks that `send("PUT", "/admin/mode?mode=normal&failNext=1").statusCode()` equals `200`
      - checks that `get("/quotes/sku-1").statusCode()` equals `503`
      - checks that `get("/quotes/sku-1").statusCode()` equals `200`
  - **test `slowModeAnswersAfterTheConfiguredDelay`**: reads as "slow mode answers after the configured delay"
    - Signature: `void slowModeAnswersAfterTheConfiguredDelay() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.SLOW)`
      - calls `provider.slowMillis(300)`
      - creates the variable `started` (of type `long`) holding `System.nanoTime()`
      - creates the variable `response` (of type `HttpResponse<String>`) holding `get("/quotes/sku-1")`
      - checks that `response.statusCode()` equals `200`
      - checks that `Duration.ofNanos(System.nanoTime() - started)` is at least `Duration.ofMillis(290)`
  - **test `hangModeIsSilentLongerThanAClientWillWaitButStillCountsTheCall`**: reads as "hang mode is silent longer than a client will wait but still counts the call"
    - Signature: `void hangModeIsSilentLongerThanAClientWillWaitButStillCountsTheCall() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.HANG)`
      - creates the variable `request` (of type `HttpRequest`) holding `HttpRequest.newBuilder(uri("/quotes/sku-1")).timeout(Duration.ofSeconds(2)).build()`
      - checks that running `client.send(request, HttpResponse.BodyHandlers.ofString())` fails
        - the failure is an instance of `HttpTimeoutException.class`
      - calls `waitUntil(() -> provider.callsReceived() == 1)`
  - **test `theAdminEndpointSwitchesTheModeAtRunTime`**: reads as "the admin endpoint switches the mode at run time"
    - Signature: `void theAdminEndpointSwitchesTheModeAtRunTime() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `changed` (of type `HttpResponse<String>`) holding `send("PUT", "/admin/mode?mode=slow&delayMs=1234")`
      - creates the variable `stats` (of type `HttpResponse<String>`) holding `get("/admin/stats")`
      - checks that `changed.statusCode()` equals `200`
      - checks that `stats.body()` contains `"\"mode\":\"slow\"", "\"slowDelayMs\":1234"`
  - **test `anUnknownModeIs400AndAGetOnTheAdminEndpointIs405`**: reads as "an unknown mode is400 and a get on the admin endpoint is405"
    - Signature: `void anUnknownModeIs400AndAGetOnTheAdminEndpointIs405() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that `send("PUT", "/admin/mode?mode=explode").statusCode()` equals `400`
      - checks that `send("PUT", "/admin/mode").statusCode()` equals `400`
      - checks that `get("/admin/mode").statusCode()` equals `405`
  - **test `maxInFlightRecordsTheMostCallsServedAtOnce`**: reads as "max in flight records the most calls served at once"
    - Signature: `void maxInFlightRecordsTheMostCallsServedAtOnce() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.SLOW)`
      - calls `provider.slowMillis(300)`
      - creates the variable `calls` (of type `List<CompletableFuture<HttpResponse<String>>>`) holding `new ArrayList<>()`
      - repeats for `int call = 0; call < 5; call++`:
        - calls `calls.add( client.sendAsync( HttpRequest.newBuilder(uri("/quotes/sku-" + call)).build(), HttpResponse.BodyHandlers.ofString()))`
      - calls `CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get()`
      - checks that `provider.maxInFlight()` equals `5`
      - checks that `provider.inFlight()` is zero
  - **test `resetZeroesTheCountersAndMetricsExposeThem`**: reads as "reset zeroes the counters and metrics expose them"
    - Signature: `void resetZeroesTheCountersAndMetricsExposeThem() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `get("/quotes/sku-1")`
      - checks that `get("/metrics").body()` contains `"provider_calls_received_total 1", "provider_in_flight_max 1"`
      - checks that `send("POST", "/admin/reset").statusCode()` equals `200`
      - checks that `provider.callsReceived()` is zero
      - checks that `get("/health").body()` equals `"ok"`
  - **test `modeNamesParseIgnoringCaseAndRejectNonsense`**: reads as "mode names parse ignoring case and reject nonsense"
    - Signature: `void modeNamesParseIgnoringCaseAndRejectNonsense()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that `ProviderMode.parse(" Fail503 ")` equals `ProviderMode.FAIL503`
      - checks that running `ProviderMode.parse("nope")` fails
        - the failure is an instance of `IllegalArgumentException.class`
      - checks that running `ProviderMode.parse(null)` fails
        - the failure is an instance of `IllegalArgumentException.class`
  - **test `theMainEntryPointStartsAProviderThatAnswersHealth`**: reads as "the main entry point starts a provider that answers health"
    - Signature: `void theMainEntryPointStartsAProviderThatAnswersHealth() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - tries this (managing `FakeProvider started = ProviderMain.start(0)` so it is closed afterwards):
        - creates the variable `request` (of type `HttpRequest`) holding `HttpRequest.newBuilder( URI.create("http://localhost:" + started.port() + "/health")) .build()`
        - checks that `client.send(request, HttpResponse.BodyHandlers.ofString()).body()` equals `"ok"`
  - **test `hangLastsAsLongAsConfiguredAndThenAnswersEmptyJson`**: reads as "hang lasts as long as configured and then answers empty json"
    - Signature: `void hangLastsAsLongAsConfiguredAndThenAnswersEmptyJson() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.HANG)`
      - calls `provider.hangMillis(200)`
      - creates the variable `response` (of type `HttpResponse<String>`) holding `get("/quotes/sku-1")`
      - checks that `response.body()` equals `"{}"`
  - **test `jsonLogEscapesQuotesAndNewlines`**: reads as "json log escapes quotes and newlines"
    - Signature: `void jsonLogEscapesQuotesAndNewlines()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that `JsonLog.escape("a \"b\"\nc\\")` equals `"a \\\"b\\\"\\nc\\\\"`
  - **method `waitUntil`**: the signature is `static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException`.
    - Signature: `private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException`
    - What the body does, in order:
      - creates the variable `deadline` (of type `long`) holding `System.nanoTime() + Duration.ofSeconds(10).toNanos()`
      - repeats while `!condition.getAsBoolean()`:
        - checks that `System.nanoTime()` as `"condition not met in 10 s"`
        - and that it is less than `deadline`
        - calls `Thread.sleep(20)`
  - **method `get`**: the signature is `HttpResponse<String> get(String path) throws IOException, InterruptedException`.
    - Signature: `private HttpResponse<String> get(String path) throws IOException, InterruptedException`
    - What the body does, in order:
      - returns `send("GET", path)`
  - **method `send`**: the signature is `HttpResponse<String> send(String method, String path) throws IOException, InterruptedException`.
    - Signature: `private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException`
    - What the body does, in order:
      - creates the variable `request` (of type `HttpRequest`) holding `HttpRequest.newBuilder(uri(path)) .method(method, HttpRequest.BodyPublishers.noBody()) .build()`
      - returns `client.send(request, HttpResponse.BodyHandlers.ofString())`
  - **method `uri`**: the signature is `URI uri(String path)`.
    - Signature: `private URI uri(String path)`
    - What the body does, in order:
      - returns `URI.create("http://localhost:" + provider.port() + path)`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@BeforeEach`: runs this method before every test, to prepare a known starting state.
- `@AfterEach`: runs this method after every test, to clean up.
- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.
- **`assertThatThrownBy`**: `assertThatThrownBy(() -> code)` runs the code and checks that it throws; the chained calls describe the exception expected.


### `loadgen/src/test/java/dev/plainround/loadgen/LoadgenTest.java`

Package `dev.plainround.loadgen`. 

The whole file, as it is in the repository:

```java
package dev.plainround.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LoadgenTest {

    @Test
    void percentilesUseTheNearestRank() {
        long[] oneToHundredMillis =
                LongStream.rangeClosed(1, 100).map(millis -> millis * 1_000_000).toArray();

        Report report = Report.of("t", oneToHundredMillis, 0, 0);

        assertThat(report.p50Millis()).isEqualTo(50.0);
        assertThat(report.p99Millis()).isEqualTo(99.0);
    }

    @Test
    void anEmptyRunReportsZeros() {
        Report report = Report.of("t", new long[0], 0, 0);

        assertThat(report.p99Millis()).isZero();
        assertThat(report.errorRate()).isZero();
    }

    @Test
    void theJsonLineCarriesEveryNumber() {
        Report report = new Report("price", 10, 2, 3, 4.0, 12.5);

        assertThat(report.toJson())
                .isEqualTo(
                        "{\"name\":\"price\",\"requests\":10,\"errors\":2,\"errorRate\":0.2000,\"stale\":3,\"p50Ms\":4.0,\"p99Ms\":12.5}");
    }

    @Test
    void optionsParseInPairs() {
        assertThat(LoadMain.parse(new String[] {"--rps", "50", "--target", "http://x/{sku}"}))
                .containsEntry("rps", "50")
                .containsEntry("target", "http://x/{sku}");
    }

    @Test
    void theRunnerHoldsTheRateAndCountsErrorsAndStaleAnswers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ok", exchange -> reply(exchange, 200, "{\"stale\":true}"));
        server.createContext("/bad", exchange -> reply(exchange, 503, "no"));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();

            Report ok = new LoadRunner().run("ok", number -> URI.create(base + "/ok"), 20, 1);
            Report bad = new LoadRunner().run("bad", number -> URI.create(base + "/bad"), 10, 1);

            assertThat(ok.requests()).isEqualTo(20);
            assertThat(ok.errors()).isZero();
            assertThat(ok.stale()).isEqualTo(20);
            assertThat(bad.errors()).isEqualTo(10);
            assertThat(bad.errorRate()).isEqualTo(1.0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mainRunsThePriceTargetAndTheSideTargetAndPrintsOneJsonLineEach() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> reply(exchange, 200, "{}"));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            String base = "http://localhost:" + server.getAddress().getPort();

            LoadMain.main(
                    new String[] {
                        "--target",
                        base + "/price/{sku}",
                        "--skus",
                        "3",
                        "--rps",
                        "10",
                        "--seconds",
                        "1",
                        "--side",
                        base + "/skus/sku-1",
                        "--side-rps",
                        "5"
                    });
        } finally {
            System.setOut(original);
            server.stop(0);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8).lines().toList())
                .anySatisfy(
                        line -> assertThat(line).contains("\"name\":\"price\"", "\"requests\":10"))
                .anySatisfy(
                        line -> assertThat(line).contains("\"name\":\"side\"", "\"requests\":5"));
    }

    @Test
    void anUnreachableTargetCountsAsErrors() throws Exception {
        Report report =
                new LoadRunner().run("down", number -> URI.create("http://localhost:1/x"), 5, 1);

        assertThat(report.errors()).isEqualTo(5);
    }

    @Test
    void aMissingTargetIsRejected() {
        assertThatThrownBy(() -> LoadMain.main(new String[] {"--rps", "1"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
```

Reading it, one piece at a time:

- **`LoadgenTest`**, a class.
  - **test `percentilesUseTheNearestRank`**: reads as "percentiles use the nearest rank"
    - Signature: `void percentilesUseTheNearestRank()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `oneToHundredMillis` (of type `long[]`) holding `LongStream.rangeClosed(1, 100).map(millis -> millis * 1_000_000).toArray()`
      - creates the variable `report` (of type `Report`) holding `Report.of("t", oneToHundredMillis, 0, 0)`
      - checks that `report.p50Millis()` equals `50.0`
      - checks that `report.p99Millis()` equals `99.0`
  - **test `anEmptyRunReportsZeros`**: reads as "an empty run reports zeros"
    - Signature: `void anEmptyRunReportsZeros()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `report` (of type `Report`) holding `Report.of("t", new long[0], 0, 0)`
      - checks that `report.p99Millis()` is zero
      - checks that `report.errorRate()` is zero
  - **test `theJsonLineCarriesEveryNumber`**: reads as "the json line carries every number"
    - Signature: `void theJsonLineCarriesEveryNumber()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `report` (of type `Report`) holding `new Report("price", 10, 2, 3, 4.0, 12.5)`
      - checks that `report.toJson()` equals `"{\"name\":\"price\",\"requests\":10,\"errors\":2,\"errorRate\":0.2000,\"stale\":3,\"p50Ms\":4.0,\"p99Ms\":12.5}"`
  - **test `optionsParseInPairs`**: reads as "options parse in pairs"
    - Signature: `void optionsParseInPairs()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that `LoadMain.parse(new String[] {"--rps", "50", "--target", "http://x/{sku}"})` has the entry `"rps", "50"`
      - and that it has the entry `"target", "http://x/{sku}"`
  - **test `theRunnerHoldsTheRateAndCountsErrorsAndStaleAnswers`**: reads as "the runner holds the rate and counts errors and stale answers"
    - Signature: `void theRunnerHoldsTheRateAndCountsErrorsAndStaleAnswers() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `server` (of type `HttpServer`) holding `HttpServer.create(new InetSocketAddress(0), 0)`
      - calls `server.createContext("/ok", exchange -> reply(exchange, 200, "{\"stale\":true}"))`
      - calls `server.createContext("/bad", exchange -> reply(exchange, 503, "no"))`
      - calls `server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())`
      - calls `server.start()`
      - tries this:
        - creates the variable `base` (of type `String`) holding `"http://localhost:" + server.getAddress().getPort()`
        - creates the variable `ok` (of type `Report`) holding `new LoadRunner().run("ok", number -> URI.create(base + "/ok"), 20, 1)`
        - creates the variable `bad` (of type `Report`) holding `new LoadRunner().run("bad", number -> URI.create(base + "/bad"), 10, 1)`
        - checks that `ok.requests()` equals `20`
        - checks that `ok.errors()` is zero
        - checks that `ok.stale()` equals `20`
        - checks that `bad.errors()` equals `10`
        - checks that `bad.errorRate()` equals `1.0`
      - whatever happened above, finally:
        - calls `server.stop(0)`
  - **test `mainRunsThePriceTargetAndTheSideTargetAndPrintsOneJsonLineEach`**: reads as "main runs the price target and the side target and prints one json line each"
    - Signature: `void mainRunsThePriceTargetAndTheSideTargetAndPrintsOneJsonLineEach() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `server` (of type `HttpServer`) holding `HttpServer.create(new InetSocketAddress(0), 0)`
      - calls `server.createContext("/", exchange -> reply(exchange, 200, "{}"))`
      - calls `server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())`
      - calls `server.start()`
      - creates the variable `original` (of type `java.io.PrintStream`) holding `System.out`
      - creates the variable `captured` (of type `java.io.ByteArrayOutputStream`) holding `new java.io.ByteArrayOutputStream()`
      - tries this:
        - calls `System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8))`
        - creates the variable `base` (of type `String`) holding `"http://localhost:" + server.getAddress().getPort()`
        - calls `LoadMain.main( new String[] { "--target", base + "/price/{sku}", "--skus", "3", "--rps", "10", "--seconds", "1", "--side", base + "/skus/sku-1", "--side-rps", "5" })`
      - whatever happened above, finally:
        - calls `System.setOut(original)`
        - calls `server.stop(0)`
      - checks that `captured.toString(StandardCharsets.UTF_8).lines().toList()` any satisfy `line -> assertThat(line).contains("\"name\":\"price\"", "\"requests\":10")`
      - and that it any satisfy `line -> assertThat(line).contains("\"name\":\"side\"", "\"requests\":5")`
  - **test `anUnreachableTargetCountsAsErrors`**: reads as "an unreachable target counts as errors"
    - Signature: `void anUnreachableTargetCountsAsErrors() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `report` (of type `Report`) holding `new LoadRunner().run("down", number -> URI.create("http://localhost:1/x"), 5, 1)`
      - checks that `report.errors()` equals `5`
  - **test `aMissingTargetIsRejected`**: reads as "a missing target is rejected"
    - Signature: `void aMissingTargetIsRejected()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that running `LoadMain.main(new String[] {"--rps", "1"})` fails
        - the failure is an instance of `IllegalArgumentException.class`
  - **method `reply`**: the signature is `static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException`.
    - Signature: `private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException`
    - What the body does, in order:
      - creates the variable `bytes` (of type `byte[]`) holding `body.getBytes(StandardCharsets.UTF_8)`
      - calls `exchange.sendResponseHeaders(status, bytes.length)`
      - calls `exchange.getResponseBody().write(bytes)`
      - calls `exchange.close()`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.
- **`assertThatThrownBy`**: `assertThatThrownBy(() -> code)` runs the code and checks that it throws; the chained calls describe the exception expected.


### `price-service/src/test/java/dev/plainround/price/ApplicationConfigurationTest.java`

Package `dev.plainround.price`. The resilience settings live in one file; this test fails if someone loosens them by accident.

The whole file, as it is in the repository:

```java
package dev.plainround.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The resilience settings live in one file; this test fails if someone loosens them by accident. No
 * Spring context.
 */
class ApplicationConfigurationTest {

    private static Properties main() throws IOException {
        Properties properties = new Properties();
        try (Reader reader =
                Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void everyProviderCallHasBothTimeoutsAndTheRetryRulesFromTheLesson() throws IOException {
        Properties properties = main();

        assertThat(properties.getProperty("price.provider.connect-timeout")).isEqualTo("1s");
        assertThat(properties.getProperty("price.provider.read-timeout")).isEqualTo("2s");
        assertThat(properties.getProperty("price.provider.retry.max-retries")).isEqualTo("2");
        assertThat(properties.getProperty("price.cache.ttl")).isEqualTo("60s");
        assertThat(properties.getProperty("price.protection")).contains("full");
    }

    @Test
    void virtualThreadsJsonLogsGracefulShutdownAndFullTracingAreOn() throws IOException {
        Properties properties = main();

        assertThat(properties.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
        assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(properties.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
    }
}
```

Reading it, one piece at a time:

- **`ApplicationConfigurationTest`**, a class. The resilience settings live in one file; this test fails if someone loosens them by accident. No Spring context.
  - **method `main`**: the signature is `static Properties main() throws IOException`.
    - Signature: `private static Properties main() throws IOException`
    - What the body does, in order:
      - creates the variable `properties` (of type `Properties`) holding `new Properties()`
      - tries this (managing `Reader reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))` so it is closed afterwards):
        - calls `properties.load(reader)`
      - returns `properties`
  - **test `everyProviderCallHasBothTimeoutsAndTheRetryRulesFromTheLesson`**: reads as "every provider call has both timeouts and the retry rules from the lesson"
    - Signature: `void everyProviderCallHasBothTimeoutsAndTheRetryRulesFromTheLesson() throws IOException`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `properties` (of type `Properties`) holding `main()`
      - checks that `properties.getProperty("price.provider.connect-timeout")` equals `"1s"`
      - checks that `properties.getProperty("price.provider.read-timeout")` equals `"2s"`
      - checks that `properties.getProperty("price.provider.retry.max-retries")` equals `"2"`
      - checks that `properties.getProperty("price.cache.ttl")` equals `"60s"`
      - checks that `properties.getProperty("price.protection")` contains `"full"`
  - **test `virtualThreadsJsonLogsGracefulShutdownAndFullTracingAreOn`**: reads as "virtual threads json logs graceful shutdown and full tracing are on"
    - Signature: `void virtualThreadsJsonLogsGracefulShutdownAndFullTracingAreOn() throws IOException`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `properties` (of type `Properties`) holding `main()`
      - checks that `properties.getProperty("spring.threads.virtual.enabled")` equals `"true"`
      - checks that `properties.getProperty("logging.structured.format.console")` equals `"logstash"`
      - checks that `properties.getProperty("server.shutdown")` equals `"graceful"`
      - checks that `properties.getProperty("management.tracing.sampling.probability")` equals `"1.0"`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.


### `price-service/src/test/java/dev/plainround/price/ArchitectureTest.java`

Package `dev.plainround.price`. The dependency rule and the proxy rule, as code.

The whole file, as it is in the repository:

```java
package dev.plainround.price;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

/** The dependency rule and the proxy rule, as code. */
@AnalyzeClasses(
        packages = "dev.plainround.price",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final List<String> PROXIED_ANNOTATIONS =
            List.of(
                    "org.springframework.resilience.annotation.Retryable",
                    "org.springframework.resilience.annotation.ConcurrencyLimit",
                    "org.springframework.transaction.annotation.Transactional");

    @ArchTest
    static final ArchRule layersOnlyDependInward =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Domain")
                    .definedBy("..domain..")
                    .layer("Application")
                    .definedBy("..application..")
                    .layer("Infrastructure")
                    .definedBy("..infrastructure..")
                    .layer("Interfaces")
                    .definedBy("..interfaces..")
                    .whereLayer("Interfaces")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Infrastructure")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Application")
                    .mayOnlyBeAccessedByLayers("Interfaces")
                    .whereLayer("Domain")
                    .mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Interfaces");

    @ArchTest
    static final ArchRule domainDependsOnNothingButTheJdk =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideOutsideOfPackages("..domain..", "java..");

    @ArchTest
    static final ArchRule applicationKnowsOnlyTheDomainTheJdkAndLogging =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideOutsideOfPackages(
                            "..domain..",
                            "..application..",
                            "java..",
                            "org.slf4j..",
                            "org.springframework.stereotype..");

    @ArchTest
    static final ArchRule noFieldInjection =
            noFields()
                    .should()
                    .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .orShould()
                    .beAnnotatedWith("jakarta.inject.Inject")
                    .orShould()
                    .beAnnotatedWith("jakarta.annotation.Resource");

    @ArchTest
    static final ArchRule controllersLiveInTheInterfacesLayer =
            classes()
                    .that()
                    .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .should()
                    .resideInAPackage("..interfaces..");

    @ArchTest
    static final ArchRule noPackageCycles =
            slices().matching("dev.plainround.price.(*)..").should().beFreeOfCycles();

    /**
     * {@code @Retryable} and {@code @ConcurrencyLimit} are applied by a proxy, which a call from
     * the same class skips.
     */
    @ArchTest
    static final ArchRule noSelfInvocationOfProxiedMethods =
            noClasses().should(callAProxiedMethodOfTheirOwnClass());

    private static ArchCondition<JavaClass> callAProxiedMethodOfTheirOwnClass() {
        return new ArchCondition<>(
                "call a @Retryable, @ConcurrencyLimit or @Transactional method of their own"
                        + " class") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    boolean proxied =
                            call.getTarget()
                                    .resolveMember()
                                    .map(
                                            method ->
                                                    PROXIED_ANNOTATIONS.stream()
                                                            .anyMatch(method::isAnnotatedWith))
                                    .orElse(false);
                    if (call.getTargetOwner().equals(javaClass) && proxied) {
                        events.add(SimpleConditionEvent.satisfied(call, call.getDescription()));
                    }
                }
            }
        };
    }
}
```

Reading it, one piece at a time:

- **`ArchitectureTest`**, a class. The dependency rule and the proxy rule, as code.
  - annotation `@AnalyzeClasses( packages = "dev.plainround.price", importOptions = ImportOption.DoNotIncludeTests.class)`: ArchUnit: says which compiled classes the rules in this class check
  - **field `PROXIED_ANNOTATIONS`** of type `List<String>`, starting as `List.of( "org.springframework.resilience.annotation.Retryable", "org.springframework.resilience.annotation.ConcurrencyLimit", "org.springframework.transaction.annotation.Transactional")`
    - Declaration: `private static final List<String> PROXIED_ANNOTATIONS = List.of( "org.springframework.resilience.annotation.Retryable", "org.springframework.resilience.annotation.ConcurrencyLimit", "org.springframework.transaction.annotation.Transactional")`
  - **field `layersOnlyDependInward`** of type `ArchRule`, starting as `layeredArchitecture() .consideringOnlyDependenciesInLayers() .layer("Domain") .definedBy("..domain..") .layer("Application") .definedBy("..application..") .layer("Infrastructure") .definedBy("..infrastructure..") .layer("Interfaces") .definedBy("..interfaces..") .whereLayer("Interfaces") .mayNotBeAccessedByAnyLayer() .whereLayer("Infrastructure") .mayNotBeAccessedByAnyLayer() .whereLayer("Application") .mayOnlyBeAccessedByLayers("Interfaces") .whereLayer("Domain") .mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Interfaces")`
    - Declaration: `static final ArchRule layersOnlyDependInward = layeredArchitecture() .consideringOnlyDependenciesInLayers() .layer("Domain") .definedBy("..domain..") .layer("Application") .definedBy("..application..") .layer("Infrastructure") .definedBy("..infrastructure..") .layer("Interfaces") .definedBy("..interfaces..") .whereLayer("Interfaces") .mayNotBeAccessedByAnyLayer() .whereLayer("Infrastructure") .mayNotBeAccessedByAnyLayer() .whereLayer("Application") .mayOnlyBeAccessedByLayers("Interfaces") .whereLayer("Domain") .mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Interfaces")`
    - annotation `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs
  - **field `domainDependsOnNothingButTheJdk`** of type `ArchRule`, starting as `noClasses() .that() .resideInAPackage("..domain..") .should() .dependOnClassesThat() .resideOutsideOfPackages("..domain..", "java..")`
    - Declaration: `static final ArchRule domainDependsOnNothingButTheJdk = noClasses() .that() .resideInAPackage("..domain..") .should() .dependOnClassesThat() .resideOutsideOfPackages("..domain..", "java..")`
    - annotation `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs
  - **field `applicationKnowsOnlyTheDomainTheJdkAndLogging`** of type `ArchRule`, starting as `noClasses() .that() .resideInAPackage("..application..") .should() .dependOnClassesThat() .resideOutsideOfPackages( "..domain..", "..application..", "java..", "org.slf4j..", "org.springframework.stereotype..")`
    - Declaration: `static final ArchRule applicationKnowsOnlyTheDomainTheJdkAndLogging = noClasses() .that() .resideInAPackage("..application..") .should() .dependOnClassesThat() .resideOutsideOfPackages( "..domain..", "..application..", "java..", "org.slf4j..", "org.springframework.stereotype..")`
    - annotation `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs
  - **field `noFieldInjection`** of type `ArchRule`, starting as `noFields() .should() .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired") .orShould() .beAnnotatedWith("jakarta.inject.Inject") .orShould() .beAnnotatedWith("jakarta.annotation.Resource")`
    - Declaration: `static final ArchRule noFieldInjection = noFields() .should() .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired") .orShould() .beAnnotatedWith("jakarta.inject.Inject") .orShould() .beAnnotatedWith("jakarta.annotation.Resource")`
    - annotation `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs
  - **field `controllersLiveInTheInterfacesLayer`** of type `ArchRule`, starting as `classes() .that() .areAnnotatedWith("org.springframework.web.bind.annotation.RestController") .should() .resideInAPackage("..interfaces..")`
    - Declaration: `static final ArchRule controllersLiveInTheInterfacesLayer = classes() .that() .areAnnotatedWith("org.springframework.web.bind.annotation.RestController") .should() .resideInAPackage("..interfaces..")`
    - annotation `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs
  - **field `noPackageCycles`** of type `ArchRule`, starting as `slices().matching("dev.plainround.price.(*)..").should().beFreeOfCycles()`
    - Declaration: `static final ArchRule noPackageCycles = slices().matching("dev.plainround.price.(*)..").should().beFreeOfCycles()`
    - annotation `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs
  - **field `noSelfInvocationOfProxiedMethods`** of type `ArchRule`: `@Retryable` and `@ConcurrencyLimit` are applied by a proxy, which a call from the same class skips.
    - Declaration: `static final ArchRule noSelfInvocationOfProxiedMethods = noClasses().should(callAProxiedMethodOfTheirOwnClass())`
    - annotation `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs
  - **method `callAProxiedMethodOfTheirOwnClass`**: the signature is `static ArchCondition<JavaClass> callAProxiedMethodOfTheirOwnClass()`.
    - Signature: `private static ArchCondition<JavaClass> callAProxiedMethodOfTheirOwnClass()`
    - What the body does, in order:
      - returns `new ArchCondition<>( "call a @Retryable, @ConcurrencyLimit or @Transactional method of their own" + " class") { @Override public void check(JavaClass javaClass, ConditionEvents events) { for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) { boolean proxied = call.getTarget() .resolveMember() .map( method -> PROXIED_ANNOTATIONS.stream() .anyMatch(method::isAnnotatedWith)) .orElse(false); if (call.getTargetOwner().equals(javaClass) && proxied) { events.add(SimpleConditionEvent.satisfied(call, call.getDescription())); } } } }`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@AnalyzeClasses`: ArchUnit: says which compiled classes the rules in this class check.
- `@ArchTest`: marks a field or method as one architecture rule that ArchUnit checks when the test class runs.
- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **Stream**: A stream processes a collection step by step: `map` converts each item, `filter` keeps some, `toList` collects the result.


### `price-service/src/test/java/dev/plainround/price/PriceServiceBehaviorIT.java`

Package `dev.plainround.price`. The lesson's measurements against the real service and the real fake provider (same code as the provider container), in one JVM.

The whole file, as it is in the repository:

```java
package dev.plainround.price;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.plainround.price.support.MutableClock;
import dev.plainround.provider.FakeProvider;
import dev.plainround.provider.ProviderMode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The lesson's measurements against the real service and the real fake provider (same code as the
 * provider container), in one JVM. The provider's own counters are the independent witness: the
 * tests never trust the client's report of how often it called.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PriceServiceBehaviorIT.TestClock.class)
class PriceServiceBehaviorIT {

    private static FakeProvider provider;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired private MutableClock clock;

    @BeforeAll
    static void startProvider() throws IOException {
        provider = new FakeProvider(0);
        provider.start();
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerAddress(DynamicPropertyRegistry registry) throws IOException {
        if (provider == null) {
            startProvider();
        }
        registry.add("price.provider.base-url", () -> "http://localhost:" + provider.port());
    }

    @BeforeEach
    void normalProvider() {
        provider.mode(ProviderMode.NORMAL);
        provider.hangMillis(1500);
        provider.slowMillis(2000);
        provider.resetCounters();
    }

    @Test
    void healthyProviderGivesAFreshPriceWithOneCall() {
        ResponseEntity<String> response = get("/price/sku-fresh");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.stale")).isFalse();
        assertThat(provider.callsReceived()).isEqualTo(1);
    }

    @Test
    void retriesAtMostThreeCallsTotalOn503() {
        provider.mode(ProviderMode.FAIL503);

        ResponseEntity<String> response = get("/price/sku-503");

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(provider.callsReceived()).as("1 original attempt + 2 retries").isEqualTo(3);
    }

    @Test
    void doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout() {
        provider.mode(ProviderMode.HANG);
        long started = System.nanoTime();

        ResponseEntity<String> response = get("/price/sku-hang");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(elapsed)
                .as("read timeout 500 ms, not the provider's 10 s")
                .isBetween(Duration.ofMillis(400), Duration.ofSeconds(4));
        assertThat(provider.callsReceived()).as("a timeout is not retried").isEqualTo(1);
    }

    @Test
    void aSlowProviderBeyondTheTimeoutIsNotRetriedEither() {
        provider.mode(ProviderMode.SLOW);
        provider.slowMillis(2000);

        ResponseEntity<String> response = get("/price/sku-slow");

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(provider.callsReceived()).isEqualTo(1);
    }

    @Test
    void aRecentlyPricedSkuIsServedStaleWhenTheProviderIsDownAndANeverPricedOneIs503() {
        get("/price/sku-recent");
        clock.advance(Duration.ofSeconds(37));
        provider.mode(ProviderMode.FAIL503);

        ResponseEntity<String> stale = get("/price/sku-recent");
        ResponseEntity<String> never = get("/price/sku-never-priced");

        assertThat(stale.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(stale.getBody(), "$.stale")).isTrue();
        assertThat(JsonPath.<Integer>read(stale.getBody(), "$.ageSeconds")).isEqualTo(37);
        assertThat(never.getStatusCode().value()).isEqualTo(503);
        assertThat(JsonPath.<String>read(never.getBody(), "$.title"))
                .isEqualTo("Provider unavailable");
        assertThat(JsonPath.<String>read(never.getBody(), "$.detail"))
                .isEqualTo("no price available for sku-never-priced");
    }

    @Test
    void aQuoteOlderThanSixtySecondsIsNotServed() {
        get("/price/sku-old");
        provider.mode(ProviderMode.FAIL503);
        clock.advance(Duration.ofSeconds(61));

        assertThat(get("/price/sku-old").getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void staleServingAlsoCoversATimeout() {
        get("/price/sku-timeout");
        provider.mode(ProviderMode.HANG);

        ResponseEntity<String> response = get("/price/sku-timeout");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.stale")).isTrue();
    }

    @Test
    void inFlightProviderCallsNeverExceedTheLimitWhileOtherEndpointsKeepAnswering()
            throws Exception {
        awaitQuietProvider();
        provider.mode(ProviderMode.SLOW);
        provider.slowMillis(400);
        provider.resetCounters();
        List<Future<Integer>> statuses = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int call = 0; call < 20; call++) {
                String sku = "sku-burst-" + call;
                statuses.add(pool.submit(() -> get("/price/" + sku).getStatusCode().value()));
            }
            long started = System.nanoTime();
            ResponseEntity<String> other = get("/skus/sku-1");
            Duration otherEndpoint = Duration.ofNanos(System.nanoTime() - started);
            assertThat(other.getStatusCode().value()).isEqualTo(200);
            assertThat(otherEndpoint)
                    .as("the catalog does not wait for the provider")
                    .isLessThan(Duration.ofMillis(300));
        }

        assertThat(provider.maxInFlight()).as("the provider's own maximum").isLessThanOrEqualTo(3);
        long refused = statuses.stream().filter(status -> value(status) == 503).count();
        assertThat(refused).as("the excess was refused, not queued").isGreaterThan(0);
    }

    @Test
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

    @Test
    void healthIsUpWithLivenessAndReadinessProbes() {
        assertThat(get("/actuator/health").getBody()).contains("\"status\":\"UP\"");
        assertThat(get("/actuator/health/readiness").getBody()).contains("UP");
    }

    private static void awaitQuietProvider() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (provider.inFlight() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(provider.inFlight()).as("calls left over from earlier tests").isZero();
    }

    private static int value(Future<Integer> status) {
        try {
            return status.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseEntity<String> get(String path) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build()
                .get()
                .uri(path)
                .retrieve()
                .toEntity(String.class);
    }
}
```

Reading it, one piece at a time:

- **`PriceServiceBehaviorIT`**, a class. The lesson's measurements against the real service and the real fake provider (same code as the provider container), in one JVM. The provider's own counters are the independent witness: the tests never trust the client's report of how often it called.
  - annotation `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`: starts the whole application inside the test (all beans, the real configuration) so the test can use it
  - annotation `@Import(PriceServiceBehaviorIT.TestClock.class)`: adds the named configuration classes to the Spring context
  - **field `provider`** of type `FakeProvider`, set once when the object is built.
    - Declaration: `private static FakeProvider provider`
  - **`TestClock`**, a class.
    - annotation `@TestConfiguration(proxyBeanMethods = false)`: a configuration class that applies only in tests
    - **method `mutableClock`**: the signature is `MutableClock mutableClock()`.
      - Signature: `MutableClock mutableClock()`
      - annotation `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object
      - annotation `@Primary`: when several beans match, Spring picks this one
      - What the body does, in order:
        - returns `new MutableClock()`
  - **method `port`**: the signature is `int port`.
    - Signature: `private int port`
    - annotation `@Value("${local.server.port}")`: injects a value from the configuration into this field or parameter
  - **field `clock`** of type `MutableClock`, set once when the object is built.
    - Declaration: `private MutableClock clock`
    - annotation `@Autowired`: asks Spring to supply the object this field or parameter needs
  - **method `startProvider`**: the signature is `static void startProvider() throws IOException`.
    - Signature: `static void startProvider() throws IOException`
    - annotation `@BeforeAll`: runs this static method once, before any test in the class
    - What the body does, in order:
      - sets `provider` to `new FakeProvider(0)`
      - calls `provider.start()`
  - **method `stopProvider`**: the signature is `static void stopProvider()`.
    - Signature: `static void stopProvider()`
    - annotation `@AfterAll`: runs this static method once, after all tests in the class
    - What the body does, in order:
      - calls `provider.close()`
  - **method `providerAddress`**: the signature is `static void providerAddress(DynamicPropertyRegistry registry) throws IOException`.
    - Signature: `static void providerAddress(DynamicPropertyRegistry registry) throws IOException`
    - annotation `@DynamicPropertySource`: Spring: lets the test set configuration keys whose values are known only at run time, such as a container port
    - What the body does, in order:
      - when `provider == null` is true:
        - calls `startProvider()`
      - calls `registry.add("price.provider.base-url", () -> "http://localhost:" + provider.port())`
  - **method `normalProvider`**: the signature is `void normalProvider()`.
    - Signature: `void normalProvider()`
    - annotation `@BeforeEach`: runs this method before every test, to prepare a known starting state
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.NORMAL)`
      - calls `provider.hangMillis(1500)`
      - calls `provider.slowMillis(2000)`
      - calls `provider.resetCounters()`
  - **test `healthyProviderGivesAFreshPriceWithOneCall`**: reads as "healthy provider gives a fresh price with one call"
    - Signature: `void healthyProviderGivesAFreshPriceWithOneCall()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `response` (of type `ResponseEntity<String>`) holding `get("/price/sku-fresh")`
      - checks that `response.getStatusCode().value()` equals `200`
      - checks that `JsonPath.<Boolean>read(response.getBody(), "$.stale")` is false
      - checks that `provider.callsReceived()` equals `1`
  - **test `retriesAtMostThreeCallsTotalOn503`**: reads as "retries at most three calls total on503"
    - Signature: `void retriesAtMostThreeCallsTotalOn503()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.FAIL503)`
      - creates the variable `response` (of type `ResponseEntity<String>`) holding `get("/price/sku-503")`
      - checks that `response.getStatusCode().value()` equals `503`
      - checks that `provider.callsReceived()` as `"1 original attempt + 2 retries"`
      - and that it equals `3`
  - **test `doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout`**: reads as "does not retry on a timeout and answers504 in about the read timeout"
    - Signature: `void doesNotRetryOnATimeoutAndAnswers504InAboutTheReadTimeout()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.HANG)`
      - creates the variable `started` (of type `long`) holding `System.nanoTime()`
      - creates the variable `response` (of type `ResponseEntity<String>`) holding `get("/price/sku-hang")`
      - creates the variable `elapsed` (of type `Duration`) holding `Duration.ofNanos(System.nanoTime() - started)`
      - checks that `response.getStatusCode().value()` equals `504`
      - checks that `response.getHeaders().getContentType().toString()` starts with `"application/problem+json"`
      - checks that `elapsed` as `"read timeout 500 ms, not the provider's 10 s"`
      - and that it lies between `Duration.ofMillis(400), Duration.ofSeconds(4)`
      - checks that `provider.callsReceived()` as `"a timeout is not retried"`
      - and that it equals `1`
  - **test `aSlowProviderBeyondTheTimeoutIsNotRetriedEither`**: reads as "a slow provider beyond the timeout is not retried either"
    - Signature: `void aSlowProviderBeyondTheTimeoutIsNotRetriedEither()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `provider.mode(ProviderMode.SLOW)`
      - calls `provider.slowMillis(2000)`
      - creates the variable `response` (of type `ResponseEntity<String>`) holding `get("/price/sku-slow")`
      - checks that `response.getStatusCode().value()` equals `504`
      - checks that `provider.callsReceived()` equals `1`
  - **test `aRecentlyPricedSkuIsServedStaleWhenTheProviderIsDownAndANeverPricedOneIs503`**: reads as "a recently priced sku is served stale when the provider is down and a never priced one is503"
    - Signature: `void aRecentlyPricedSkuIsServedStaleWhenTheProviderIsDownAndANeverPricedOneIs503()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `get("/price/sku-recent")`
      - calls `clock.advance(Duration.ofSeconds(37))`
      - calls `provider.mode(ProviderMode.FAIL503)`
      - creates the variable `stale` (of type `ResponseEntity<String>`) holding `get("/price/sku-recent")`
      - creates the variable `never` (of type `ResponseEntity<String>`) holding `get("/price/sku-never-priced")`
      - checks that `stale.getStatusCode().value()` equals `200`
      - checks that `JsonPath.<Boolean>read(stale.getBody(), "$.stale")` is true
      - checks that `JsonPath.<Integer>read(stale.getBody(), "$.ageSeconds")` equals `37`
      - checks that `never.getStatusCode().value()` equals `503`
      - checks that `JsonPath.<String>read(never.getBody(), "$.title")` equals `"Provider unavailable"`
      - checks that `JsonPath.<String>read(never.getBody(), "$.detail")` equals `"no price available for sku-never-priced"`
  - **test `aQuoteOlderThanSixtySecondsIsNotServed`**: reads as "a quote older than sixty seconds is not served"
    - Signature: `void aQuoteOlderThanSixtySecondsIsNotServed()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `get("/price/sku-old")`
      - calls `provider.mode(ProviderMode.FAIL503)`
      - calls `clock.advance(Duration.ofSeconds(61))`
      - checks that `get("/price/sku-old").getStatusCode().value()` equals `503`
  - **test `staleServingAlsoCoversATimeout`**: reads as "stale serving also covers a timeout"
    - Signature: `void staleServingAlsoCoversATimeout()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `get("/price/sku-timeout")`
      - calls `provider.mode(ProviderMode.HANG)`
      - creates the variable `response` (of type `ResponseEntity<String>`) holding `get("/price/sku-timeout")`
      - checks that `response.getStatusCode().value()` equals `200`
      - checks that `JsonPath.<Boolean>read(response.getBody(), "$.stale")` is true
  - **test `inFlightProviderCallsNeverExceedTheLimitWhileOtherEndpointsKeepAnswering`**: reads as "in flight provider calls never exceed the limit while other endpoints keep answering"
    - Signature: `void inFlightProviderCallsNeverExceedTheLimitWhileOtherEndpointsKeepAnswering() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `awaitQuietProvider()`
      - calls `provider.mode(ProviderMode.SLOW)`
      - calls `provider.slowMillis(400)`
      - calls `provider.resetCounters()`
      - creates the variable `statuses` (of type `List<Future<Integer>>`) holding `new ArrayList<>()`
      - tries this (managing `ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()` so it is closed afterwards):
        - repeats for `int call = 0; call < 20; call++`:
          - creates the variable `sku` (of type `String`) holding `"sku-burst-" + call`
          - calls `statuses.add(pool.submit(() -> get("/price/" + sku).getStatusCode().value()))`
        - creates the variable `started` (of type `long`) holding `System.nanoTime()`
        - creates the variable `other` (of type `ResponseEntity<String>`) holding `get("/skus/sku-1")`
        - creates the variable `otherEndpoint` (of type `Duration`) holding `Duration.ofNanos(System.nanoTime() - started)`
        - checks that `other.getStatusCode().value()` equals `200`
        - checks that `otherEndpoint` as `"the catalog does not wait for the provider"`
        - and that it is less than `Duration.ofMillis(300)`
      - checks that `provider.maxInFlight()` as `"the provider's own maximum"`
      - and that it is at most `3`
      - creates the variable `refused` (of type `long`) holding `statuses.stream().filter(status -> value(status) == 503).count()`
      - checks that `refused` as `"the excess was refused, not queued"`
      - and that it is greater than `0`
  - **test `metricsAndTraceIdsAreExposed`**: reads as "metrics and trace ids are exposed"
    - Signature: `void metricsAndTraceIdsAreExposed()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `price` (of type `ResponseEntity<String>`) holding `get("/price/sku-metrics")`
      - calls `provider.mode(ProviderMode.FAIL503)`
      - calls `get("/price/sku-metrics-2")`
      - creates the variable `prometheus` (of type `String`) holding `get("/actuator/prometheus").getBody()`
      - checks that `price.getHeaders().getFirst("X-Trace-Id")` matches `"[0-9a-f]{32}"`
      - checks that `prometheus` contains `"price_provider_attempts_total{", "result=\"success\"", "result=\"unavailable\""`
      - checks that `prometheus` contains `"price_provider_failures_total{", "price_provider_call_seconds_bucket"`
      - checks that `prometheus` contains `"price_cache_lookups_total{"`
  - **test `healthIsUpWithLivenessAndReadinessProbes`**: reads as "health is up with liveness and readiness probes"
    - Signature: `void healthIsUpWithLivenessAndReadinessProbes()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that `get("/actuator/health").getBody()` contains `"\"status\":\"UP\""`
      - checks that `get("/actuator/health/readiness").getBody()` contains `"UP"`
  - **method `awaitQuietProvider`**: the signature is `static void awaitQuietProvider() throws InterruptedException`.
    - Signature: `private static void awaitQuietProvider() throws InterruptedException`
    - What the body does, in order:
      - creates the variable `deadline` (of type `long`) holding `System.nanoTime() + Duration.ofSeconds(15).toNanos()`
      - repeats while `provider.inFlight() > 0 && System.nanoTime() < deadline`:
        - calls `Thread.sleep(50)`
      - checks that `provider.inFlight()` as `"calls left over from earlier tests"`
      - and that it is zero
  - **method `value`**: the signature is `static int value(Future<Integer> status)`.
    - Signature: `private static int value(Future<Integer> status)`
    - What the body does, in order:
      - tries this:
        - returns `status.get()`
      - if this fails with `Exception exception`:
        - throws a new `IllegalStateException` created with `exception`
  - **method `get`**: the signature is `ResponseEntity<String> get(String path)`.
    - Signature: `private ResponseEntity<String> get(String path)`
    - What the body does, in order:
      - returns `RestClient.builder() .baseUrl("http://localhost:" + port) .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {}) .build() .get() .uri(path) .retrieve() .toEntity(String.class)`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@SpringBootTest`: starts the whole application inside the test (all beans, the real configuration) so the test can use it.
- `@Import`: adds the named configuration classes to the Spring context.
- `@TestConfiguration`: a configuration class that applies only in tests.
- `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object.
- `@Primary`: when several beans match, Spring picks this one.
- `@Value`: injects a value from the configuration into this field or parameter.
- `@Autowired`: asks Spring to supply the object this field or parameter needs.
- `@BeforeAll`: runs this static method once, before any test in the class.
- `@AfterAll`: runs this static method once, after all tests in the class.
- `@DynamicPropertySource`: Spring: lets the test set configuration keys whose values are known only at run time, such as a container port.
- `@BeforeEach`: runs this method before every test, to prepare a known starting state.
- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.
- **Stream**: A stream processes a collection step by step: `map` converts each item, `filter` keeps some, `toList` collects the result.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.


### `price-service/src/test/java/dev/plainround/price/application/PriceServiceTest.java`

Package `dev.plainround.price.application`. 

The whole file, as it is in the repository:

```java
package dev.plainround.price.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.plainround.price.domain.Catalog;
import dev.plainround.price.domain.CatalogEntry;
import dev.plainround.price.domain.NoPriceAvailable;
import dev.plainround.price.domain.PriceResult;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.ProviderUnavailable;
import dev.plainround.price.domain.Quote;
import dev.plainround.price.domain.QuoteCache;
import dev.plainround.price.domain.QuoteSource;
import dev.plainround.price.domain.SkuNotFound;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PriceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Quote GOOD = new Quote(new BigDecimal("42.00"), NOW.minusSeconds(37));

    private final QuoteSource source = mock(QuoteSource.class);
    private final QuoteCache cache = mock(QuoteCache.class);
    private final PriceService service =
            new PriceService(source, cache, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aFreshQuoteIsReturnedAndRemembered() {
        when(source.fetch("sku-1")).thenReturn(GOOD);

        PriceResult result = service.price("sku-1");

        assertThat(result.stale()).isFalse();
        verify(cache).put("sku-1", GOOD);
    }

    @Test
    void whenTheProviderFailsTheLastGoodQuoteIsServedStaleWithItsAge() {
        when(source.fetch("sku-1")).thenThrow(new ProviderUnavailable("503"));
        when(cache.get("sku-1")).thenReturn(Optional.of(GOOD));

        PriceResult result = service.price("sku-1");

        assertThat(result.stale()).isTrue();
        assertThat(result.ageSeconds()).isEqualTo(37);
    }

    @Test
    void aSkuNeverPricedIsNoPriceAvailableAndKeepsTheReason() {
        when(source.fetch("sku-new")).thenThrow(new ProviderTimeout(new RuntimeException("slow")));
        when(cache.get("sku-new")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.price("sku-new"))
                .isInstanceOfSatisfying(
                        NoPriceAvailable.class,
                        error -> assertThat(error.failure()).isInstanceOf(ProviderTimeout.class));
    }

    @Test
    void theCatalogServiceListsAndNamesAMissingSku() {
        Catalog catalog = mock(Catalog.class);
        CatalogEntry entry = new CatalogEntry("sku-1", "Item 1");
        when(catalog.all()).thenReturn(List.of(entry));
        when(catalog.find("sku-1")).thenReturn(Optional.of(entry));
        when(catalog.find("nope")).thenReturn(Optional.empty());
        CatalogService catalogService = new CatalogService(catalog);

        assertThat(catalogService.list()).containsExactly(entry);
        assertThat(catalogService.get("sku-1")).isEqualTo(entry);
        assertThatThrownBy(() -> catalogService.get("nope")).isInstanceOf(SkuNotFound.class);
    }
}
```

Reading it, one piece at a time:

- **`PriceServiceTest`**, a class.
  - **field `NOW`** of type `Instant`, starting as `Instant.parse("2026-10-27T10:00:00Z")`
    - Declaration: `private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z")`
  - **field `GOOD`** of type `Quote`, starting as `new Quote(new BigDecimal("42.00"), NOW.minusSeconds(37))`
    - Declaration: `private static final Quote GOOD = new Quote(new BigDecimal("42.00"), NOW.minusSeconds(37))`
  - **field `source`** of type `QuoteSource`, starting as `mock(QuoteSource.class)`
    - Declaration: `private final QuoteSource source = mock(QuoteSource.class)`
  - **field `cache`** of type `QuoteCache`, starting as `mock(QuoteCache.class)`
    - Declaration: `private final QuoteCache cache = mock(QuoteCache.class)`
  - **field `service`** of type `PriceService`, starting as `new PriceService(source, cache, Clock.fixed(NOW, ZoneOffset.UTC))`
    - Declaration: `private final PriceService service = new PriceService(source, cache, Clock.fixed(NOW, ZoneOffset.UTC))`
  - **test `aFreshQuoteIsReturnedAndRemembered`**: reads as "a fresh quote is returned and remembered"
    - Signature: `void aFreshQuoteIsReturnedAndRemembered()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - teaches the fake: when `source.fetch("sku-1")` is called, it answers with `GOOD`
      - creates the variable `result` (of type `PriceResult`) holding `service.price("sku-1")`
      - checks that `result.stale()` is false
      - checks, with Mockito, that the fake was used as follows: `verify(cache).put("sku-1", GOOD)`
  - **test `whenTheProviderFailsTheLastGoodQuoteIsServedStaleWithItsAge`**: reads as "when the provider fails the last good quote is served stale with its age"
    - Signature: `void whenTheProviderFailsTheLastGoodQuoteIsServedStaleWithItsAge()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - teaches the fake: when `source.fetch("sku-1")` is called, it fails with `new ProviderUnavailable("503")`
      - teaches the fake: when `cache.get("sku-1")` is called, it answers with `Optional.of(GOOD)`
      - creates the variable `result` (of type `PriceResult`) holding `service.price("sku-1")`
      - checks that `result.stale()` is true
      - checks that `result.ageSeconds()` equals `37`
  - **test `aSkuNeverPricedIsNoPriceAvailableAndKeepsTheReason`**: reads as "a sku never priced is no price available and keeps the reason"
    - Signature: `void aSkuNeverPricedIsNoPriceAvailableAndKeepsTheReason()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - teaches the fake: when `source.fetch("sku-new")` is called, it fails with `new ProviderTimeout(new RuntimeException("slow"))`
      - teaches the fake: when `cache.get("sku-new")` is called, it answers with `Optional.empty()`
      - checks that running `service.price("sku-new")` fails
        - the failure isInstanceOfSatisfying `NoPriceAvailable.class, error -> assertThat(error.failure()).isInstanceOf(ProviderTimeout.class)`
  - **test `theCatalogServiceListsAndNamesAMissingSku`**: reads as "the catalog service lists and names a missing sku"
    - Signature: `void theCatalogServiceListsAndNamesAMissingSku()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `catalog` (of type `Catalog`) holding `mock(Catalog.class)`
      - creates the variable `entry` (of type `CatalogEntry`) holding `new CatalogEntry("sku-1", "Item 1")`
      - teaches the fake: when `catalog.all()` is called, it answers with `List.of(entry)`
      - teaches the fake: when `catalog.find("sku-1")` is called, it answers with `Optional.of(entry)`
      - teaches the fake: when `catalog.find("nope")` is called, it answers with `Optional.empty()`
      - creates the variable `catalogService` (of type `CatalogService`) holding `new CatalogService(catalog)`
      - checks that `catalogService.list()` contains exactly, in order, `entry`
      - checks that `catalogService.get("sku-1")` equals `entry`
      - checks that running `catalogService.get("nope")` fails
        - the failure is an instance of `SkuNotFound.class`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.
- **`assertThatThrownBy`**: `assertThatThrownBy(() -> code)` runs the code and checks that it throws; the chained calls describe the exception expected.
- **Mockito stubbing**: `given(mock.call()).willReturn(x)` (or `when(...).thenReturn(x)`) teaches a Mockito fake what to answer.
- **Mockito `verify`**: `verify(mock).call()` checks that the fake was called that way.
- **Mockito `mock`**: `mock(Type.class)` creates a fake object of that type that records the calls made to it.


### `price-service/src/test/java/dev/plainround/price/domain/DomainTest.java`

Package `dev.plainround.price.domain`. 

The whole file, as it is in the repository:

```java
package dev.plainround.price.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DomainTest {

    private static final Instant THEN = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void aFreshResultIsNotStaleAndHasNoAge() {
        PriceResult result = PriceResult.fresh(new Quote(new BigDecimal("42.00"), THEN));

        assertThat(result.stale()).isFalse();
        assertThat(result.ageSeconds()).isZero();
    }

    @Test
    void aStaleResultCarriesTheAgeOfTheQuote() {
        PriceResult result =
                PriceResult.stale(new Quote(new BigDecimal("42.00"), THEN), THEN.plusSeconds(37));

        assertThat(result.stale()).isTrue();
        assertThat(result.ageSeconds()).isEqualTo(37);
        assertThat(result.price()).isEqualByComparingTo("42.00");
    }

    @Test
    void aQuoteWithoutAPositivePriceIsAProviderFailure() {
        assertThatThrownBy(() -> new Quote(null, THEN)).isInstanceOf(ProviderUnavailable.class);
        assertThatThrownBy(() -> new Quote(BigDecimal.ZERO, THEN))
                .isInstanceOf(ProviderUnavailable.class);
    }

    @Test
    void theNamedFailuresSayWhy() {
        assertThat(new ProviderTimeout(new RuntimeException("slow")))
                .hasMessageContaining("in time");
        assertThat(new ProviderBusy(new RuntimeException("full")))
                .hasMessageContaining("in flight");
        assertThat(new ProviderUnavailable("x", new RuntimeException("cause"))).hasMessage("x");
        NoPriceAvailable noPrice = new NoPriceAvailable("sku-1", new ProviderUnavailable("down"));
        assertThat(noPrice).hasMessage("no price available for sku-1");
        assertThat(noPrice.failure()).isInstanceOf(ProviderUnavailable.class);
        assertThat(new SkuNotFound("nope")).hasMessage("no product with sku nope");
    }
}
```

Reading it, one piece at a time:

- **`DomainTest`**, a class.
  - **field `THEN`** of type `Instant`, starting as `Instant.parse("2026-10-27T10:00:00Z")`
    - Declaration: `private static final Instant THEN = Instant.parse("2026-10-27T10:00:00Z")`
  - **test `aFreshResultIsNotStaleAndHasNoAge`**: reads as "a fresh result is not stale and has no age"
    - Signature: `void aFreshResultIsNotStaleAndHasNoAge()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `result` (of type `PriceResult`) holding `PriceResult.fresh(new Quote(new BigDecimal("42.00"), THEN))`
      - checks that `result.stale()` is false
      - checks that `result.ageSeconds()` is zero
  - **test `aStaleResultCarriesTheAgeOfTheQuote`**: reads as "a stale result carries the age of the quote"
    - Signature: `void aStaleResultCarriesTheAgeOfTheQuote()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `result` (of type `PriceResult`) holding `PriceResult.stale(new Quote(new BigDecimal("42.00"), THEN), THEN.plusSeconds(37))`
      - checks that `result.stale()` is true
      - checks that `result.ageSeconds()` equals `37`
      - checks that `result.price()` equals (by comparison) `"42.00"`
  - **test `aQuoteWithoutAPositivePriceIsAProviderFailure`**: reads as "a quote without a positive price is a provider failure"
    - Signature: `void aQuoteWithoutAPositivePriceIsAProviderFailure()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that running `new Quote(null, THEN)` fails
        - the failure is an instance of `ProviderUnavailable.class`
      - checks that running `new Quote(BigDecimal.ZERO, THEN)` fails
        - the failure is an instance of `ProviderUnavailable.class`
  - **test `theNamedFailuresSayWhy`**: reads as "the named failures say why"
    - Signature: `void theNamedFailuresSayWhy()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - checks that `new ProviderTimeout(new RuntimeException("slow"))` has a message containing `"in time"`
      - checks that `new ProviderBusy(new RuntimeException("full"))` has a message containing `"in flight"`
      - checks that `new ProviderUnavailable("x", new RuntimeException("cause"))` has the message `"x"`
      - creates the variable `noPrice` (of type `NoPriceAvailable`) holding `new NoPriceAvailable("sku-1", new ProviderUnavailable("down"))`
      - checks that `noPrice` has the message `"no price available for sku-1"`
      - checks that `noPrice.failure()` is an instance of `ProviderUnavailable.class`
      - checks that `new SkuNotFound("nope")` has the message `"no product with sku nope"`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.
- **`assertThatThrownBy`**: `assertThatThrownBy(() -> code)` runs the code and checks that it throws; the chained calls describe the exception expected.


### `price-service/src/test/java/dev/plainround/price/infrastructure/InMemoryQuoteCacheTest.java`

Package `dev.plainround.price.infrastructure`. 

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plainround.price.domain.Quote;
import dev.plainround.price.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryQuoteCacheTest {

    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private InMemoryQuoteCache cache(int maxEntries) {
        return new InMemoryQuoteCache(
                clock, new CacheProperties(Duration.ofSeconds(60), maxEntries), meters);
    }

    private Quote quote(String price) {
        return new Quote(new BigDecimal(price), clock.instant());
    }

    @Test
    void aQuoteIsServedUntilSixtySecondsAfterItWasWritten() {
        InMemoryQuoteCache cache = cache(10);
        cache.put("sku-1", quote("42.00"));

        clock.advance(Duration.ofSeconds(60));
        assertThat(cache.get("sku-1")).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.get("sku-1")).isEmpty();
    }

    @Test
    void readingDoesNotExtendTheLifeOfAQuote() {
        InMemoryQuoteCache cache = cache(10);
        cache.put("sku-1", quote("42.00"));

        for (int read = 0; read < 5; read++) {
            clock.advance(Duration.ofSeconds(12));
            cache.get("sku-1");
        }
        clock.advance(Duration.ofSeconds(1));

        assertThat(cache.get("sku-1"))
                .as("61 seconds after the write, however often it was read")
                .isEmpty();
    }

    @Test
    void aNewQuoteReplacesTheOldOneAndRestartsTheClock() {
        InMemoryQuoteCache cache = cache(10);
        cache.put("sku-1", quote("42.00"));
        clock.advance(Duration.ofSeconds(50));
        cache.put("sku-1", quote("43.00"));
        clock.advance(Duration.ofSeconds(50));

        assertThat(cache.get("sku-1")).map(Quote::price).contains(new BigDecimal("43.00"));
    }

    @Test
    void theOldestEntryIsDroppedWhenTheBoundIsReached() {
        InMemoryQuoteCache cache = cache(2);
        cache.put("a-sku", quote("1.00"));
        cache.put("b-sku", quote("2.00"));
        cache.put("c-sku", quote("3.00"));

        assertThat(cache.get("a-sku")).isEmpty();
        assertThat(cache.get("b-sku")).isPresent();
        assertThat(cache.get("c-sku")).isPresent();
    }

    @Test
    void hitsAndMissesAreCounted() {
        InMemoryQuoteCache cache = cache(10);
        cache.put("sku-1", quote("42.00"));

        cache.get("sku-1");
        cache.get("other");

        assertThat(meters.counter("price.cache.lookups", "result", "hit").count()).isEqualTo(1);
        assertThat(meters.counter("price.cache.lookups", "result", "miss").count()).isEqualTo(1);
    }

    @Test
    void theUnprotectedVariantsRememberNothingAndTheCatalogHasTenProducts() {
        NoQuoteCache none = new NoQuoteCache();
        none.put("sku-1", quote("42.00"));

        assertThat(none.get("sku-1")).isEmpty();
        InMemoryCatalog catalog = new InMemoryCatalog();
        assertThat(catalog.all()).hasSize(10);
        assertThat(catalog.find("sku-3")).isPresent();
        assertThat(catalog.find("nope")).isEmpty();
    }
}
```

Reading it, one piece at a time:

- **`InMemoryQuoteCacheTest`**, a class.
  - **field `clock`** of type `MutableClock`, starting as `new MutableClock()`
    - Declaration: `private final MutableClock clock = new MutableClock()`
  - **field `meters`** of type `SimpleMeterRegistry`, starting as `new SimpleMeterRegistry()`
    - Declaration: `private final SimpleMeterRegistry meters = new SimpleMeterRegistry()`
  - **method `cache`**: the signature is `InMemoryQuoteCache cache(int maxEntries)`.
    - Signature: `private InMemoryQuoteCache cache(int maxEntries)`
    - What the body does, in order:
      - returns `new InMemoryQuoteCache( clock, new CacheProperties(Duration.ofSeconds(60), maxEntries), meters)`
  - **method `quote`**: the signature is `Quote quote(String price)`.
    - Signature: `private Quote quote(String price)`
    - What the body does, in order:
      - returns `new Quote(new BigDecimal(price), clock.instant())`
  - **test `aQuoteIsServedUntilSixtySecondsAfterItWasWritten`**: reads as "a quote is served until sixty seconds after it was written"
    - Signature: `void aQuoteIsServedUntilSixtySecondsAfterItWasWritten()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `cache` (of type `InMemoryQuoteCache`) holding `cache(10)`
      - calls `cache.put("sku-1", quote("42.00"))`
      - calls `clock.advance(Duration.ofSeconds(60))`
      - checks that `cache.get("sku-1")` is present
      - calls `clock.advance(Duration.ofSeconds(1))`
      - checks that `cache.get("sku-1")` is empty
  - **test `readingDoesNotExtendTheLifeOfAQuote`**: reads as "reading does not extend the life of a quote"
    - Signature: `void readingDoesNotExtendTheLifeOfAQuote()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `cache` (of type `InMemoryQuoteCache`) holding `cache(10)`
      - calls `cache.put("sku-1", quote("42.00"))`
      - repeats for `int read = 0; read < 5; read++`:
        - calls `clock.advance(Duration.ofSeconds(12))`
        - calls `cache.get("sku-1")`
      - calls `clock.advance(Duration.ofSeconds(1))`
      - checks that `cache.get("sku-1")` as `"61 seconds after the write, however often it was read"`
      - and that it is empty
  - **test `aNewQuoteReplacesTheOldOneAndRestartsTheClock`**: reads as "a new quote replaces the old one and restarts the clock"
    - Signature: `void aNewQuoteReplacesTheOldOneAndRestartsTheClock()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `cache` (of type `InMemoryQuoteCache`) holding `cache(10)`
      - calls `cache.put("sku-1", quote("42.00"))`
      - calls `clock.advance(Duration.ofSeconds(50))`
      - calls `cache.put("sku-1", quote("43.00"))`
      - calls `clock.advance(Duration.ofSeconds(50))`
      - checks that `cache.get("sku-1")` map `Quote::price`
      - and that it contains `new BigDecimal("43.00")`
  - **test `theOldestEntryIsDroppedWhenTheBoundIsReached`**: reads as "the oldest entry is dropped when the bound is reached"
    - Signature: `void theOldestEntryIsDroppedWhenTheBoundIsReached()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `cache` (of type `InMemoryQuoteCache`) holding `cache(2)`
      - calls `cache.put("a-sku", quote("1.00"))`
      - calls `cache.put("b-sku", quote("2.00"))`
      - calls `cache.put("c-sku", quote("3.00"))`
      - checks that `cache.get("a-sku")` is empty
      - checks that `cache.get("b-sku")` is present
      - checks that `cache.get("c-sku")` is present
  - **test `hitsAndMissesAreCounted`**: reads as "hits and misses are counted"
    - Signature: `void hitsAndMissesAreCounted()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `cache` (of type `InMemoryQuoteCache`) holding `cache(10)`
      - calls `cache.put("sku-1", quote("42.00"))`
      - calls `cache.get("sku-1")`
      - calls `cache.get("other")`
      - checks that `meters.counter("price.cache.lookups", "result", "hit").count()` equals `1`
      - checks that `meters.counter("price.cache.lookups", "result", "miss").count()` equals `1`
  - **test `theUnprotectedVariantsRememberNothingAndTheCatalogHasTenProducts`**: reads as "the unprotected variants remember nothing and the catalog has ten products"
    - Signature: `void theUnprotectedVariantsRememberNothingAndTheCatalogHasTenProducts()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `none` (of type `NoQuoteCache`) holding `new NoQuoteCache()`
      - calls `none.put("sku-1", quote("42.00"))`
      - checks that `none.get("sku-1")` is empty
      - creates the variable `catalog` (of type `InMemoryCatalog`) holding `new InMemoryCatalog()`
      - checks that `catalog.all()` has the size `10`
      - checks that `catalog.find("sku-3")` is present
      - checks that `catalog.find("nope")` is empty

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.


### `price-service/src/test/java/dev/plainround/price/infrastructure/ProviderErrorsTest.java`

Package `dev.plainround.price.infrastructure`. 

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plainround.price.domain.ProviderBusy;
import dev.plainround.price.domain.ProviderFailure;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.ProviderUnavailable;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class ProviderErrorsTest {

    @Test
    void aTimeoutInsideAnIoErrorIsAProviderTimeout() {
        ResourceAccessException readTimeout =
                new ResourceAccessException(
                        "I/O error", new HttpTimeoutException("request timed out"));
        ResourceAccessException socketTimeout =
                new ResourceAccessException(
                        "I/O error", new SocketTimeoutException("Read timed out"));

        assertThat(ProviderErrors.translate(readTimeout)).isInstanceOf(ProviderTimeout.class);
        assertThat(ProviderErrors.translate(socketTimeout)).isInstanceOf(ProviderTimeout.class);
    }

    @Test
    void aRefusedConnectionIsUnavailableNotATimeout() {
        ResourceAccessException refused =
                new ResourceAccessException(
                        "I/O error", new ConnectException("Connection refused"));

        assertThat(ProviderErrors.translate(refused)).isInstanceOf(ProviderUnavailable.class);
    }

    @Test
    void aRejectionByTheConcurrencyLimitIsBusy() {
        ProviderFailure failure =
                ProviderErrors.translate(new InvocationRejectedException("limit reached", this));

        assertThat(failure).isInstanceOf(ProviderBusy.class);
        assertThat(ProviderErrors.reason(failure)).isEqualTo("busy");
    }

    @Test
    void aServerErrorAndAnythingElseAreUnavailable() {
        HttpServerErrorException serverError =
                HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "unavailable",
                        new HttpHeaders(),
                        new byte[0],
                        null);
        HttpClientErrorException clientError =
                HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "nf", new HttpHeaders(), new byte[0], null);

        assertThat(ProviderErrors.translate(serverError)).hasMessageContaining("503");
        assertThat(ProviderErrors.translate(clientError)).isInstanceOf(ProviderUnavailable.class);
        assertThat(ProviderErrors.reason(ProviderErrors.translate(serverError)))
                .isEqualTo("unavailable");
    }

    @Test
    void aDomainFailurePassesThroughUnchanged() {
        ProviderTimeout timeout = new ProviderTimeout(new RuntimeException("slow"));

        assertThat(ProviderErrors.translate(timeout)).isSameAs(timeout);
        assertThat(ProviderErrors.reason(timeout)).isEqualTo("timeout");
    }
}
```

Reading it, one piece at a time:

- **`ProviderErrorsTest`**, a class.
  - **test `aTimeoutInsideAnIoErrorIsAProviderTimeout`**: reads as "a timeout inside an io error is a provider timeout"
    - Signature: `void aTimeoutInsideAnIoErrorIsAProviderTimeout()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `readTimeout` (of type `ResourceAccessException`) holding `new ResourceAccessException( "I/O error", new HttpTimeoutException("request timed out"))`
      - creates the variable `socketTimeout` (of type `ResourceAccessException`) holding `new ResourceAccessException( "I/O error", new SocketTimeoutException("Read timed out"))`
      - checks that `ProviderErrors.translate(readTimeout)` is an instance of `ProviderTimeout.class`
      - checks that `ProviderErrors.translate(socketTimeout)` is an instance of `ProviderTimeout.class`
  - **test `aRefusedConnectionIsUnavailableNotATimeout`**: reads as "a refused connection is unavailable not a timeout"
    - Signature: `void aRefusedConnectionIsUnavailableNotATimeout()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `refused` (of type `ResourceAccessException`) holding `new ResourceAccessException( "I/O error", new ConnectException("Connection refused"))`
      - checks that `ProviderErrors.translate(refused)` is an instance of `ProviderUnavailable.class`
  - **test `aRejectionByTheConcurrencyLimitIsBusy`**: reads as "a rejection by the concurrency limit is busy"
    - Signature: `void aRejectionByTheConcurrencyLimitIsBusy()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `failure` (of type `ProviderFailure`) holding `ProviderErrors.translate(new InvocationRejectedException("limit reached", this))`
      - checks that `failure` is an instance of `ProviderBusy.class`
      - checks that `ProviderErrors.reason(failure)` equals `"busy"`
  - **test `aServerErrorAndAnythingElseAreUnavailable`**: reads as "a server error and anything else are unavailable"
    - Signature: `void aServerErrorAndAnythingElseAreUnavailable()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `serverError` (of type `HttpServerErrorException`) holding `HttpServerErrorException.create( HttpStatus.SERVICE_UNAVAILABLE, "unavailable", new HttpHeaders(), new byte[0], null)`
      - creates the variable `clientError` (of type `HttpClientErrorException`) holding `HttpClientErrorException.create( HttpStatus.NOT_FOUND, "nf", new HttpHeaders(), new byte[0], null)`
      - checks that `ProviderErrors.translate(serverError)` has a message containing `"503"`
      - checks that `ProviderErrors.translate(clientError)` is an instance of `ProviderUnavailable.class`
      - checks that `ProviderErrors.reason(ProviderErrors.translate(serverError))` equals `"unavailable"`
  - **test `aDomainFailurePassesThroughUnchanged`**: reads as "a domain failure passes through unchanged"
    - Signature: `void aDomainFailurePassesThroughUnchanged()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates the variable `timeout` (of type `ProviderTimeout`) holding `new ProviderTimeout(new RuntimeException("slow"))`
      - checks that `ProviderErrors.translate(timeout)` is the very same object as `timeout`
      - checks that `ProviderErrors.reason(timeout)` equals `"timeout"`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.


### `price-service/src/test/java/dev/plainround/price/infrastructure/ProviderPropertiesTest.java`

Package `dev.plainround.price.infrastructure`. 

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class ProviderPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                    .withUserConfiguration(Config.class)
                    .withPropertyValues("price.provider.base-url=http://localhost:9");

    @Test
    void defaultsAreOneSecondToConnectAndTwoToRead() {
        runner.run(
                context -> {
                    ProviderProperties properties = context.getBean(ProviderProperties.class);
                    assertThat(properties.connectTimeout().toMillis()).isEqualTo(1000);
                    assertThat(properties.readTimeout().toMillis()).isEqualTo(2000);
                    assertThat(context.getBean(CacheProperties.class).ttl().toSeconds())
                            .isEqualTo(60);
                });
    }

    @Test
    void aZeroOrNegativeTimeoutStopsTheApplicationAtStartup() {
        runner.withPropertyValues("price.provider.read-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("price.provider.connect-timeout=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMissingBaseUrlOrAnAbsurdCacheBoundStopsTheApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("price.cache.max-entries=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties({ProviderProperties.class, CacheProperties.class})
    static class Config {}
}
```

Reading it, one piece at a time:

- **`ProviderPropertiesTest`**, a class.
  - **field `runner`** of type `ApplicationContextRunner`, starting as `new ApplicationContextRunner() .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class)) .withUserConfiguration(Config.class) .withPropertyValues("price.provider.base-url=http:`
    - Declaration: `private final ApplicationContextRunner runner = new ApplicationContextRunner() .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class)) .withUserConfiguration(Config.class) .withPropertyValues("price.provider.base-url=http:`
  - **test `defaultsAreOneSecondToConnectAndTwoToRead`**: reads as "defaults are one second to connect and two to read"
    - Signature: `void defaultsAreOneSecondToConnectAndTwoToRead()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `runner.run( context -> { ProviderProperties properties = context.getBean(ProviderProperties.class); assertThat(properties.connectTimeout().toMillis()).isEqualTo(1000); assertThat(properties.readTimeout().toMillis()).isEqualTo(2000); assertThat(context.getBean(CacheProperties.class).ttl().toSeconds()) .isEqualTo(60); })`
  - **test `aZeroOrNegativeTimeoutStopsTheApplicationAtStartup`**: reads as "a zero or negative timeout stops the application at startup"
    - Signature: `void aZeroOrNegativeTimeoutStopsTheApplicationAtStartup()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `runner.withPropertyValues("price.provider.read-timeout=0s") .run(context -> assertThat(context).hasFailed())`
      - calls `runner.withPropertyValues("price.provider.connect-timeout=-1s") .run(context -> assertThat(context).hasFailed())`
  - **test `aMissingBaseUrlOrAnAbsurdCacheBoundStopsTheApplication`**: reads as "a missing base url or an absurd cache bound stops the application"
    - Signature: `void aMissingBaseUrlOrAnAbsurdCacheBoundStopsTheApplication()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - creates and uses a new object: `new ApplicationContextRunner() .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class)) .withUserConfiguration(Config.class) .run(context -> assertThat(context).hasFailed())`
      - calls `runner.withPropertyValues("price.cache.max-entries=0") .run(context -> assertThat(context).hasFailed())`
  - **`Config`**, a class.
    - annotation `@EnableConfigurationProperties({ProviderProperties.class, CacheProperties.class})`: registers the named properties class so Spring fills it from the configuration

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.
- `@EnableConfigurationProperties`: registers the named properties class so Spring fills it from the configuration.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.


### `price-service/src/test/java/dev/plainround/price/infrastructure/ResilienceAnnotationsTest.java`

Package `dev.plainround.price.infrastructure`. Step 4's counter and step 3's retry bound, on small beans, with no network involved.

The whole file, as it is in the repository:

```java
package dev.plainround.price.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;

/** Step 4's counter and step 3's retry bound, on small beans, with no network involved. */
class ResilienceAnnotationsTest {

    @Test
    void twentyConcurrentCallsAgainstALimitOfThreeNeverShowMoreThanThreeInFlight()
            throws Exception {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(Config.class)) {
            Slow slow = context.getBean(Slow.class);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> calls = new ArrayList<>();
                for (int call = 0; call < 20; call++) {
                    calls.add(
                            pool.submit(
                                    () -> {
                                        slow.blockingCall();
                                        return null;
                                    }));
                }
                for (Future<?> call : calls) {
                    call.get();
                }
            }

            assertThat(slow.maxInFlight()).isEqualTo(3);
        }
    }

    @Test
    void theRejectPolicyRefusesTheExcessAtOnceInsteadOfQueueing() throws Exception {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(Config.class)) {
            Slow slow = context.getBean(Slow.class);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger admitted = new AtomicInteger();

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> calls = new ArrayList<>();
                for (int call = 0; call < 6; call++) {
                    calls.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            slow.rejectingCall(release);
                                            admitted.incrementAndGet();
                                        } catch (InvocationRejectedException refused) {
                                            rejected.incrementAndGet();
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }));
                }
                while (rejected.get() < 4) {
                    Thread.sleep(10);
                }
                release.countDown();
                for (Future<?> call : calls) {
                    call.get();
                }
            }

            assertThat(admitted.get()).isEqualTo(2);
            assertThat(rejected.get()).isEqualTo(4);
        }
    }

    @Test
    void retryableStopsAfterTheConfiguredNumberOfRetriesAndOnlyForTheNamedException() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(Config.class)) {
            Flaky flaky = context.getBean(Flaky.class);

            assertThatThrownBy(flaky::alwaysUnavailable).isInstanceOf(IllegalStateException.class);
            assertThat(flaky.unavailableCalls()).as("1 original + 2 retries").isEqualTo(3);

            assertThatThrownBy(flaky::alwaysDifferent).isInstanceOf(IllegalArgumentException.class);
            assertThat(flaky.differentCalls()).as("not the named exception: no retry").isEqualTo(1);
        }
    }

    @Configuration
    @EnableResilientMethods
    static class Config {

        @Bean
        Slow slow() {
            return new Slow();
        }

        @Bean
        Flaky flaky() {
            return new Flaky();
        }
    }

    static class Slow {
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();

        // Read through a method: the bean is a proxy, and a field read on the proxy would see its
        // own empty copy.
        public int maxInFlight() {
            return maxInFlight.get();
        }

        @ConcurrencyLimit(3)
        public void blockingCall() throws InterruptedException {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
        public void rejectingCall(CountDownLatch release) throws InterruptedException {
            release.await();
        }
    }

    static class Flaky {
        private final AtomicInteger unavailableCalls = new AtomicInteger();
        private final AtomicInteger differentCalls = new AtomicInteger();

        public int unavailableCalls() {
            return unavailableCalls.get();
        }

        public int differentCalls() {
            return differentCalls.get();
        }

        @Retryable(
                includes = IllegalStateException.class,
                maxRetries = 2,
                delay = 1,
                jitter = 1,
                multiplier = 2.0)
        public void alwaysUnavailable() {
            unavailableCalls.incrementAndGet();
            throw new IllegalStateException("503");
        }

        @Retryable(includes = IllegalStateException.class, maxRetries = 2, delay = 1)
        public void alwaysDifferent() {
            differentCalls.incrementAndGet();
            throw new IllegalArgumentException("timeout");
        }
    }
}
```

Reading it, one piece at a time:

- **`ResilienceAnnotationsTest`**, a class. Step 4's counter and step 3's retry bound, on small beans, with no network involved.
  - **test `twentyConcurrentCallsAgainstALimitOfThreeNeverShowMoreThanThreeInFlight`**: reads as "twenty concurrent calls against a limit of three never show more than three in flight"
    - Signature: `void twentyConcurrentCallsAgainstALimitOfThreeNeverShowMoreThanThreeInFlight() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - tries this (managing `AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)` so it is closed afterwards):
        - creates the variable `slow` (of type `Slow`) holding `context.getBean(Slow.class)`
        - tries this (managing `ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()` so it is closed afterwards):
          - creates the variable `calls` (of type `List<Future<?>>`) holding `new ArrayList<>()`
          - repeats for `int call = 0; call < 20; call++`:
            - calls `calls.add( pool.submit( () -> { slow.blockingCall(); return null; }))`
          - repeats for `Future<?> call : calls`:
            - calls `call.get()`
        - checks that `slow.maxInFlight()` equals `3`
  - **test `theRejectPolicyRefusesTheExcessAtOnceInsteadOfQueueing`**: reads as "the reject policy refuses the excess at once instead of queueing"
    - Signature: `void theRejectPolicyRefusesTheExcessAtOnceInsteadOfQueueing() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - tries this (managing `AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)` so it is closed afterwards):
        - creates the variable `slow` (of type `Slow`) holding `context.getBean(Slow.class)`
        - creates the variable `release` (of type `CountDownLatch`) holding `new CountDownLatch(1)`
        - creates the variable `rejected` (of type `AtomicInteger`) holding `new AtomicInteger()`
        - creates the variable `admitted` (of type `AtomicInteger`) holding `new AtomicInteger()`
        - tries this (managing `ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()` so it is closed afterwards):
          - creates the variable `calls` (of type `List<Future<?>>`) holding `new ArrayList<>()`
          - repeats for `int call = 0; call < 6; call++`:
            - calls `calls.add( pool.submit( () -> { try { slow.rejectingCall(release); admitted.incrementAndGet(); } catch (InvocationRejectedException refused) { rejected.incrementAndGet(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }))`
          - repeats while `rejected.get() < 4`:
            - calls `Thread.sleep(10)`
          - calls `release.countDown()`
          - repeats for `Future<?> call : calls`:
            - calls `call.get()`
        - checks that `admitted.get()` equals `2`
        - checks that `rejected.get()` equals `4`
  - **test `retryableStopsAfterTheConfiguredNumberOfRetriesAndOnlyForTheNamedException`**: reads as "retryable stops after the configured number of retries and only for the named exception"
    - Signature: `void retryableStopsAfterTheConfiguredNumberOfRetriesAndOnlyForTheNamedException()`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - tries this (managing `AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)` so it is closed afterwards):
        - creates the variable `flaky` (of type `Flaky`) holding `context.getBean(Flaky.class)`
        - checks that running `flaky::alwaysUnavailable` fails
          - the failure is an instance of `IllegalStateException.class`
        - checks that `flaky.unavailableCalls()` as `"1 original + 2 retries"`
        - and that it equals `3`
        - checks that running `flaky::alwaysDifferent` fails
          - the failure is an instance of `IllegalArgumentException.class`
        - checks that `flaky.differentCalls()` as `"not the named exception: no retry"`
        - and that it equals `1`
  - **`Config`**, a class.
    - annotation `@Configuration`: marks a class whose `@Bean` methods define objects for Spring to manage
    - annotation `@EnableResilientMethods`: switches on the `@Retryable` and `@ConcurrencyLimit` handling in this application
    - **method `slow`**: the signature is `Slow slow()`.
      - Signature: `Slow slow()`
      - annotation `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object
      - What the body does, in order:
        - returns `new Slow()`
    - **method `flaky`**: the signature is `Flaky flaky()`.
      - Signature: `Flaky flaky()`
      - annotation `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object
      - What the body does, in order:
        - returns `new Flaky()`
  - **`Slow`**, a class.
    - **field `maxInFlight`** of type `AtomicInteger`, starting as `new AtomicInteger()`
      - Declaration: `private final AtomicInteger maxInFlight = new AtomicInteger()`
    - **field `inFlight`** of type `AtomicInteger`, starting as `new AtomicInteger()`
      - Declaration: `private final AtomicInteger inFlight = new AtomicInteger()`
    - **method `maxInFlight`**: the signature is `int maxInFlight()`.
      - Signature: `public int maxInFlight()`
      - What the body does, in order:
        - returns `maxInFlight.get()`
    - **method `blockingCall`**: the signature is `void blockingCall() throws InterruptedException`.
      - Signature: `public void blockingCall() throws InterruptedException`
      - annotation `@ConcurrencyLimit(3)`: caps how many threads may be inside this method at once; extra callers wait or are rejected as configured
      - What the body does, in order:
        - calls `maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max)`
        - tries this:
          - calls `Thread.sleep(50)`
        - whatever happened above, finally:
          - calls `inFlight.decrementAndGet()`
    - **method `rejectingCall`**: the signature is `void rejectingCall(CountDownLatch release) throws InterruptedException`.
      - Signature: `public void rejectingCall(CountDownLatch release) throws InterruptedException`
      - annotation `@ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)`: caps how many threads may be inside this method at once; extra callers wait or are rejected as configured
      - What the body does, in order:
        - calls `release.await()`
  - **`Flaky`**, a class.
    - **field `unavailableCalls`** of type `AtomicInteger`, starting as `new AtomicInteger()`
      - Declaration: `private final AtomicInteger unavailableCalls = new AtomicInteger()`
    - **field `differentCalls`** of type `AtomicInteger`, starting as `new AtomicInteger()`
      - Declaration: `private final AtomicInteger differentCalls = new AtomicInteger()`
    - **method `unavailableCalls`**: the signature is `int unavailableCalls()`.
      - Signature: `public int unavailableCalls()`
      - What the body does, in order:
        - returns `unavailableCalls.get()`
    - **method `differentCalls`**: the signature is `int differentCalls()`.
      - Signature: `public int differentCalls()`
      - What the body does, in order:
        - returns `differentCalls.get()`
    - **method `alwaysUnavailable`**: the signature is `void alwaysUnavailable()`.
      - Signature: `public void alwaysUnavailable()`
      - annotation `@Retryable( includes = IllegalStateException.class, maxRetries = 2, delay = 1, jitter = 1, multiplier = 2.0)`: asks Spring Framework 7's resilience support to call this method again when it fails with the listed exceptions
      - What the body does, in order:
        - calls `unavailableCalls.incrementAndGet()`
        - throws a new `IllegalStateException` created with `"503"`
    - **method `alwaysDifferent`**: the signature is `void alwaysDifferent()`.
      - Signature: `public void alwaysDifferent()`
      - annotation `@Retryable(includes = IllegalStateException.class, maxRetries = 2, delay = 1)`: asks Spring Framework 7's resilience support to call this method again when it fails with the listed exceptions
      - What the body does, in order:
        - calls `differentCalls.incrementAndGet()`
        - throws a new `IllegalArgumentException` created with `"timeout"`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.
- `@Configuration`: marks a class whose `@Bean` methods define objects for Spring to manage.
- `@EnableResilientMethods`: switches on the `@Retryable` and `@ConcurrencyLimit` handling in this application.
- `@Bean`: tells Spring to call this method once at startup and keep the returned object as a shared, managed object.
- `@ConcurrencyLimit`: caps how many threads may be inside this method at once; extra callers wait or are rejected as configured.
- `@Retryable`: asks Spring Framework 7's resilience support to call this method again when it fails with the listed exceptions.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **lambda (`->`)**: An arrow `a -> b` is a lambda: a small unnamed function. The part before the arrow lists its inputs and the part after is what it does.
- **method reference (`::`)**: `Type::method` is a short way to pass an existing method where a function is expected.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **null**: `null` means "no object". Calling a method on null throws `NullPointerException`.
- **try/catch**: `try { ... } catch (X e) { ... }` runs the first block and, if it throws `X`, runs the second.
- **AssertJ `assertThat`**: `assertThat(actual).isXxx(...)` is AssertJ: it checks the actual value and throws an assertion error, which fails the test, when the check is false.
- **`assertThatThrownBy`**: `assertThatThrownBy(() -> code)` runs the code and checks that it throws; the chained calls describe the exception expected.


### `price-service/src/test/java/dev/plainround/price/interfaces/web/PriceControllerTest.java`

Package `dev.plainround.price.interfaces.web`. The web layer only: the controllers, the advice and the MVC machinery; the services are test doubles.

The whole file, as it is in the repository:

```java
package dev.plainround.price.interfaces.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.plainround.price.application.CatalogService;
import dev.plainround.price.application.PriceService;
import dev.plainround.price.domain.CatalogEntry;
import dev.plainround.price.domain.NoPriceAvailable;
import dev.plainround.price.domain.PriceResult;
import dev.plainround.price.domain.ProviderBusy;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.ProviderUnavailable;
import dev.plainround.price.domain.SkuNotFound;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The web layer only: the controllers, the advice and the MVC machinery; the services are test
 * doubles.
 */
@WebMvcTest({PriceController.class, CatalogController.class})
class PriceControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PriceService prices;
    @MockitoBean private CatalogService catalog;
    @MockitoBean private Tracer tracer;

    @Test
    void aFreshPriceIs200WithStaleFalse() throws Exception {
        when(prices.price("sku-1")).thenReturn(new PriceResult(new BigDecimal("42.00"), false, 0));

        mockMvc.perform(get("/price/sku-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(42.00))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void aStalePriceIs200WithTheLabelAndTheAge() throws Exception {
        when(prices.price("sku-1")).thenReturn(new PriceResult(new BigDecimal("42.00"), true, 37));

        mockMvc.perform(get("/price/sku-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.ageSeconds").value(37));
    }

    @Test
    void aNeverPricedSkuWithAFailingProviderIs503WithRetryAfter() throws Exception {
        when(prices.price("sku-new"))
                .thenThrow(new NoPriceAvailable("sku-new", new ProviderUnavailable("down")));

        mockMvc.perform(get("/price/sku-new"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Provider unavailable"))
                .andExpect(jsonPath("$.detail").value("no price available for sku-new"));
    }

    @Test
    void aRefusedCallIsAlsoA503() throws Exception {
        when(prices.price("sku-new"))
                .thenThrow(
                        new NoPriceAvailable(
                                "sku-new", new ProviderBusy(new RuntimeException("full"))));

        mockMvc.perform(get("/price/sku-new")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void aNeverPricedSkuWhoseCallTimedOutIs504() throws Exception {
        when(prices.price("sku-new"))
                .thenThrow(
                        new NoPriceAvailable(
                                "sku-new", new ProviderTimeout(new RuntimeException("slow"))));

        mockMvc.perform(get("/price/sku-new"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Provider timed out"));
    }

    @Test
    void aSkuWithOddCharactersIs400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/price/bad sku!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sku"));
    }

    @Test
    void theCatalogListsProductsAndNamesAMissingOne() throws Exception {
        when(catalog.list()).thenReturn(List.of(new CatalogEntry("sku-1", "Item 1")));
        when(catalog.get("nope")).thenThrow(new SkuNotFound("nope"));

        mockMvc.perform(get("/skus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Item 1"));
        mockMvc.perform(get("/skus/nope")).andExpect(status().isNotFound());
    }

    @Test
    void anUnexpectedErrorIs500WithoutLeakingTheMessage() throws Exception {
        when(prices.price("boom")).thenThrow(new IllegalStateException("internal-host-7 exploded"));

        mockMvc.perform(get("/price/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "internal-host-7"))));
    }
}
```

Reading it, one piece at a time:

- **`PriceControllerTest`**, a class. The web layer only: the controllers, the advice and the MVC machinery; the services are test doubles.
  - annotation `@WebMvcTest({PriceController.class, CatalogController.class})`: starts only the web layer (controllers, JSON conversion, validation) for a fast test
  - **field `mockMvc`** of type `MockMvc`, set once when the object is built.
    - Declaration: `private MockMvc mockMvc`
    - annotation `@Autowired`: asks Spring to supply the object this field or parameter needs
  - **field `prices`** of type `PriceService`, set once when the object is built.
    - Declaration: `private PriceService prices`
    - annotation `@MockitoBean`: puts a Mockito fake in the Spring context in place of the real bean of that type
  - **field `catalog`** of type `CatalogService`, set once when the object is built.
    - Declaration: `private CatalogService catalog`
    - annotation `@MockitoBean`: puts a Mockito fake in the Spring context in place of the real bean of that type
  - **field `tracer`** of type `Tracer`, set once when the object is built.
    - Declaration: `private Tracer tracer`
    - annotation `@MockitoBean`: puts a Mockito fake in the Spring context in place of the real bean of that type
  - **test `aFreshPriceIs200WithStaleFalse`**: reads as "a fresh price is200 with stale false"
    - Signature: `void aFreshPriceIs200WithStaleFalse() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - teaches the fake: when `prices.price("sku-1")` is called, it answers with `new PriceResult(new BigDecimal("42.00"), false, 0)`
        - then `mockMvc`
      - sends the fake HTTP request `get("/price/sku-1")`
        - expects `status().isOk()`
        - expects `jsonPath("$.price").value(42.00)`
        - expects `jsonPath("$.stale").value(false)`
  - **test `aStalePriceIs200WithTheLabelAndTheAge`**: reads as "a stale price is200 with the label and the age"
    - Signature: `void aStalePriceIs200WithTheLabelAndTheAge() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - teaches the fake: when `prices.price("sku-1")` is called, it answers with `new PriceResult(new BigDecimal("42.00"), true, 37)`
        - then `mockMvc`
      - sends the fake HTTP request `get("/price/sku-1")`
        - expects `status().isOk()`
        - expects `jsonPath("$.stale").value(true)`
        - expects `jsonPath("$.ageSeconds").value(37)`
  - **test `aNeverPricedSkuWithAFailingProviderIs503WithRetryAfter`**: reads as "a never priced sku with a failing provider is503 with retry after"
    - Signature: `void aNeverPricedSkuWithAFailingProviderIs503WithRetryAfter() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `when(prices.price("sku-new")) .thenThrow(new NoPriceAvailable("sku-new", new ProviderUnavailable("down")))`
        - then `mockMvc`
      - sends the fake HTTP request `get("/price/sku-new")`
        - expects `status().isServiceUnavailable()`
        - expects `header().string("Retry-After", "1")`
        - expects `content().contentTypeCompatibleWith("application/problem+json")`
        - expects `jsonPath("$.title").value("Provider unavailable")`
        - expects `jsonPath("$.detail").value("no price available for sku-new")`
  - **test `aRefusedCallIsAlsoA503`**: reads as "a refused call is also a503"
    - Signature: `void aRefusedCallIsAlsoA503() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `when(prices.price("sku-new")) .thenThrow( new NoPriceAvailable( "sku-new", new ProviderBusy(new RuntimeException("full"))))`
        - then `mockMvc`
      - sends the fake HTTP request `get("/price/sku-new")`
        - expects `status().isServiceUnavailable()`
  - **test `aNeverPricedSkuWhoseCallTimedOutIs504`**: reads as "a never priced sku whose call timed out is504"
    - Signature: `void aNeverPricedSkuWhoseCallTimedOutIs504() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - calls `when(prices.price("sku-new")) .thenThrow( new NoPriceAvailable( "sku-new", new ProviderTimeout(new RuntimeException("slow"))))`
        - then `mockMvc`
      - sends the fake HTTP request `get("/price/sku-new")`
        - expects `status().isGatewayTimeout()`
        - expects `content().contentTypeCompatibleWith("application/problem+json")`
        - expects `jsonPath("$.title").value("Provider timed out")`
  - **test `aSkuWithOddCharactersIs400NamingTheParameter`**: reads as "a sku with odd characters is400 naming the parameter"
    - Signature: `void aSkuWithOddCharactersIs400NamingTheParameter() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
        - then `mockMvc`
      - sends the fake HTTP request `get("/price/bad sku!")`
        - expects `status().isBadRequest()`
        - expects `jsonPath("$.errors[0].field").value("sku")`
  - **test `theCatalogListsProductsAndNamesAMissingOne`**: reads as "the catalog lists products and names a missing one"
    - Signature: `void theCatalogListsProductsAndNamesAMissingOne() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - teaches the fake: when `catalog.list()` is called, it answers with `List.of(new CatalogEntry("sku-1", "Item 1"))`
      - teaches the fake: when `catalog.get("nope")` is called, it fails with `new SkuNotFound("nope")`
        - then `mockMvc`
      - sends the fake HTTP request `get("/skus")`
        - expects `status().isOk()`
        - expects `jsonPath("$[0].name").value("Item 1")`
        - then `mockMvc`
      - sends the fake HTTP request `get("/skus/nope")`
        - expects `status().isNotFound()`
  - **test `anUnexpectedErrorIs500WithoutLeakingTheMessage`**: reads as "an unexpected error is500 without leaking the message"
    - Signature: `void anUnexpectedErrorIs500WithoutLeakingTheMessage() throws Exception`
    - annotation `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws
    - What the body does, in order:
      - teaches the fake: when `prices.price("boom")` is called, it fails with `new IllegalStateException("internal-host-7 exploded")`
        - then `mockMvc`
      - sends the fake HTTP request `get("/price/boom")`
        - expects `status().isInternalServerError()`
        - expects `content() .string( org.hamcrest.Matchers.not( org.hamcrest.Matchers.containsString( "internal-host-7")))`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@WebMvcTest`: starts only the web layer (controllers, JSON conversion, validation) for a fast test.
- `@Autowired`: asks Spring to supply the object this field or parameter needs.
- `@MockitoBean`: puts a Mockito fake in the Spring context in place of the real bean of that type.
- `@Test`: marks a method as one test; the test runner calls it and the test passes unless something inside throws.

Language features in this file, explained:

- **static**: `static` means the member belongs to the class itself, not to one object; you use it without creating an object.
- **private**: `private` hides a member from every other class.
- **List**: A `List` is an ordered collection of items; `List.of(...)` builds one that cannot be changed afterwards.
- **throws**: `throws X` in a signature says the method may fail with exception `X`; an exception is an object that stops normal flow and travels up the call stack until something catches it.
- **MockMvc**: `MockMvc` sends a fake HTTP request straight into the controllers without a network, then lets the test inspect the response.
- **Mockito stubbing**: `given(mock.call()).willReturn(x)` (or `when(...).thenReturn(x)`) teaches a Mockito fake what to answer.


### `price-service/src/test/java/dev/plainround/price/support/MutableClock.java`

Package `dev.plainround.price.support`. A clock tests can move forward, so a 60-second cache expiry is tested without waiting 60 seconds.

The whole file, as it is in the repository:

```java
package dev.plainround.price.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move forward, so a 60-second cache expiry is tested without waiting 60 seconds.
 */
public final class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-10-27T10:00:00Z");

    /**
     * Moves the clock forward.
     *
     * @param amount how far
     */
    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
```

Reading it, one piece at a time:

- **`MutableClock`**, a class that extends `Clock`. A clock tests can move forward, so a 60-second cache expiry is tested without waiting 60 seconds.
  - **field `now`** of type `Instant`, starting as `Instant.parse("2026-10-27T10:00:00Z")`
    - Declaration: `private volatile Instant now = Instant.parse("2026-10-27T10:00:00Z")`
  - **method `advance`**: Moves the clock forward.
    - Signature: `public void advance(Duration amount)`
    - input `amount`: how far
    - What the body does, in order:
      - sets `now` to `now.plus(amount)`
  - **method `getZone`**: the signature is `ZoneId getZone()`.
    - Signature: `public ZoneId getZone()`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - returns `ZoneOffset.UTC`
  - **method `withZone`**: the signature is `Clock withZone(ZoneId zone)`.
    - Signature: `public Clock withZone(ZoneId zone)`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - returns `this`
  - **method `instant`**: the signature is `Instant instant()`.
    - Signature: `public Instant instant()`
    - annotation `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists
    - What the body does, in order:
      - returns `now`

Annotations in this file (an annotation is a label starting with `@` that tools read):

- `@Override`: says this method replaces one with the same name from a parent class or interface; the compiler checks that such a parent method exists.

Language features in this file, explained:

- **final**: `final` on a field or variable means it is assigned once and never changed; on a class it forbids subclasses.
- **private**: `private` hides a member from every other class.
- **public**: `public` lets any class use the member.
- **extends**: `extends X` makes the class a child of `X`: it inherits what `X` has and may replace parts.


## Try it yourself

1. Run one test class: `./mvnw -q test -Dtest=BookTest` (use any class name from above).
2. Break one expected value in a test, run it, and read the failure message. Then undo your edit.
3. Run `./mvnw verify` to run everything, including the integration tests and the coverage check.

## Mistakes people make

- Writing a test that cannot fail. Always see it fail once.
- Sharing state between tests, so that one test passes only when another ran first. Every test here builds its own data.
- Asserting on implementation details instead of behavior.

## Check yourself

1. How does a test fail? *By throwing, usually from an assertion.*
2. What do `*Test` and `*IT` mean? *Unit tests run by Surefire; integration tests run by Failsafe.*
3. Why are test names long sentences? *The failure report shows only the name.*

## Further reading

[JUnit](junit.md), [AssertJ](assertj.md), [Mockito](mockito.md), [Spring Boot Test](spring-boot-test.md) and [ArchUnit](archunit.md) in this folder.
