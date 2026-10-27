package dev.plainround.price.domain;

import java.util.Optional;

/**
 * Port: the last good quote per SKU, remembered so it can be served, labeled stale, while the
 * provider is down.
 */
public interface QuoteCache {

    /**
     * Remembers a quote, replacing the previous one for the SKU.
     *
     * @param sku the product code
     * @param quote the quote to remember
     */
    void put(String sku, Quote quote);

    /**
     * Returns the remembered quote if it is still young enough to serve.
     *
     * @param sku the product code
     * @return the quote, or empty if there is none or it has expired
     */
    Optional<Quote> get(String sku);
}
