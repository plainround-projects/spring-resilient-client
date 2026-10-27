package dev.plainround.price.interfaces.web;

import dev.plainround.price.domain.PriceResult;
import java.math.BigDecimal;

/**
 * A price as the API shows it.
 *
 * @param price the unit price
 * @param stale true when the provider failed and this is its last good answer
 * @param ageSeconds how many seconds ago the provider gave this price, 0 when fresh
 */
public record PriceResponse(BigDecimal price, boolean stale, long ageSeconds) {

    /**
     * Converts a domain result.
     *
     * @param result the result
     * @return its API shape
     */
    public static PriceResponse from(PriceResult result) {
        return new PriceResponse(result.price(), result.stale(), result.ageSeconds());
    }
}
