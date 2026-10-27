package dev.plainround.price.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move forward, so a 60-second cache expiry is tested without waiting 60 seconds.
 */
public final class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-10-27T10:00:00Z");

    /**
     * Moves the clock forward.
     *
     * @param amount how far
     */
    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
