package dev.plainround.price.domain;

import java.util.List;
import java.util.Optional;

/** Port: the products the shop sells. */
public interface Catalog {

    /**
     * Lists every product.
     *
     * @return the entries
     */
    List<CatalogEntry> all();

    /**
     * Looks a product up.
     *
     * @param sku the product code
     * @return the entry, or empty if there is none
     */
    Optional<CatalogEntry> find(String sku);
}
