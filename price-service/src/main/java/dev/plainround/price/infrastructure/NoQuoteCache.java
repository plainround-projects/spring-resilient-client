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
