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
