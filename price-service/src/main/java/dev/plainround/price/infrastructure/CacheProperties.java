package dev.plainround.price.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long a good quote may be served stale, and how many are remembered.
 *
 * @param ttl the longest a quote may be served after the provider gave it
 * @param maxEntries the most SKUs remembered; the oldest entry goes first
 */
@Validated
@ConfigurationProperties("price.cache")
public record CacheProperties(
        @DefaultValue("60s") Duration ttl,
        @DefaultValue("10000") @Min(1) @Max(1_000_000) int maxEntries) {}
