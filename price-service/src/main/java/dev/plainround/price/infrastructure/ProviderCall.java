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
