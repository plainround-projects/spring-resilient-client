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
