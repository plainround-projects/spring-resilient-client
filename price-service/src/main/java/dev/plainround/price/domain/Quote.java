package dev.plainround.price.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price the provider gave us at a moment in time.
 *
 * @param price the unit price, positive
 * @param fetchedAt when the provider answered; the cache measures a quote's age from here
 */
public record Quote(BigDecimal price, Instant fetchedAt) {

    /** Checks the invariants. */
    public Quote {
        if (price == null || price.signum() <= 0) {
            throw new ProviderUnavailable("the provider sent no usable price");
        }
    }
}
