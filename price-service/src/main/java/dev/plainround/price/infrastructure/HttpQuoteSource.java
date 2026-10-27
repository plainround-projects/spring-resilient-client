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
