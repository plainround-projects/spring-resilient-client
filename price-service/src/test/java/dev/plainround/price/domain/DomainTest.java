package dev.plainround.price.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DomainTest {

    private static final Instant THEN = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void aFreshResultIsNotStaleAndHasNoAge() {
        PriceResult result = PriceResult.fresh(new Quote(new BigDecimal("42.00"), THEN));

        assertThat(result.stale()).isFalse();
        assertThat(result.ageSeconds()).isZero();
    }

    @Test
    void aStaleResultCarriesTheAgeOfTheQuote() {
        PriceResult result =
                PriceResult.stale(new Quote(new BigDecimal("42.00"), THEN), THEN.plusSeconds(37));

        assertThat(result.stale()).isTrue();
        assertThat(result.ageSeconds()).isEqualTo(37);
        assertThat(result.price()).isEqualByComparingTo("42.00");
    }

    @Test
    void aQuoteWithoutAPositivePriceIsAProviderFailure() {
        assertThatThrownBy(() -> new Quote(null, THEN)).isInstanceOf(ProviderUnavailable.class);
        assertThatThrownBy(() -> new Quote(BigDecimal.ZERO, THEN))
                .isInstanceOf(ProviderUnavailable.class);
    }

    @Test
    void theNamedFailuresSayWhy() {
        assertThat(new ProviderTimeout(new RuntimeException("slow")))
                .hasMessageContaining("in time");
        assertThat(new ProviderBusy(new RuntimeException("full")))
                .hasMessageContaining("in flight");
        assertThat(new ProviderUnavailable("x", new RuntimeException("cause"))).hasMessage("x");
        NoPriceAvailable noPrice = new NoPriceAvailable("sku-1", new ProviderUnavailable("down"));
        assertThat(noPrice).hasMessage("no price available for sku-1");
        assertThat(noPrice.failure()).isInstanceOf(ProviderUnavailable.class);
        assertThat(new SkuNotFound("nope")).hasMessage("no product with sku nope");
    }
}
