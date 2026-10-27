package dev.plainround.price.domain;

/** Port: where fresh quotes come from. */
public interface QuoteSource {

    /**
     * Asks the provider for a price.
     *
     * @param sku the product code
     * @return the quote
     * @throws ProviderFailure if the provider cannot answer
     */
    Quote fetch(String sku);
}
