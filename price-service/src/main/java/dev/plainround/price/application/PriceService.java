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
