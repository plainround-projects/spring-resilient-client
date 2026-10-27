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
