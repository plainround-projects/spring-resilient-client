package dev.plainround.price.application;

import dev.plainround.price.domain.Catalog;
import dev.plainround.price.domain.CatalogEntry;
import dev.plainround.price.domain.SkuNotFound;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reads the product catalog, which does not depend on the provider. */
@Service
public class CatalogService {

    private final Catalog catalog;

    /**
     * Wires the service.
     *
     * @param catalog the products
     */
    public CatalogService(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Lists every product.
     *
     * @return the entries
     */
    public List<CatalogEntry> list() {
        return catalog.all();
    }

    /**
     * Returns one product.
     *
     * @param sku the product code
     * @return the entry
     * @throws SkuNotFound if there is none
     */
    public CatalogEntry get(String sku) {
        return catalog.find(sku).orElseThrow(() -> new SkuNotFound(sku));
    }
}
