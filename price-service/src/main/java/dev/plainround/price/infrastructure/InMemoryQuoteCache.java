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
