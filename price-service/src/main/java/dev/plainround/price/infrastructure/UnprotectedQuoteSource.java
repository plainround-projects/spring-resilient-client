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
