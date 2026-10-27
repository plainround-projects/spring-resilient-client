package dev.plainround.price.domain;

/**
 * A product the shop sells. Its lookup needs no provider, so it keeps answering while the provider
 * is down.
 *
 * @param sku the product code
 * @param name the display name
 */
public record CatalogEntry(String sku, String name) {}
