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
