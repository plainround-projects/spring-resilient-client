package dev.plainround.price.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * What the price API answers: the price, whether it is the last one we knew rather than a fresh
 * one, and its age.
 *
 * @param price the unit price
 * @param stale true when the provider failed and this is its last good answer
 * @param ageSeconds how many seconds ago the provider gave this price, 0 for a fresh one
 */
public record PriceResult(BigDecimal price, boolean stale, long ageSeconds) {

    /**
     * Wraps a quote that has just been fetched.
     *
     * @param quote the fresh quote
     * @return a result that is not stale
     */
    public static PriceResult fresh(Quote quote) {
        return new PriceResult(quote.price(), false, 0);
    }

    /**
     * Wraps the last good quote, labeled stale with its age.
     *
     * @param quote the cached quote
     * @param now the current time
     * @return a result that is stale
     */
    public static PriceResult stale(Quote quote, Instant now) {
        return new PriceResult(
                quote.price(), true, Duration.between(quote.fetchedAt(), now).toSeconds());
    }
}
