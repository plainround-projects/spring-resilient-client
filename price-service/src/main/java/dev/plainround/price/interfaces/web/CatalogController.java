package dev.plainround.price.interfaces.web;

import dev.plainround.price.application.CatalogService;
import dev.plainround.price.domain.CatalogEntry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP face of the catalog: an endpoint that never calls the provider, used to show the provider's
 * sickness does not spread.
 */
@RestController
@RequestMapping("/skus")
public class CatalogController {

    private final CatalogService service;

    /**
     * Wires the controller.
     *
     * @param service the catalog use case
     */
    public CatalogController(CatalogService service) {
        this.service = service;
    }

    /**
     * Lists the products.
     *
     * @return the entries
     */
    @GetMapping
    public List<CatalogEntry> list() {
        return service.list();
    }

    /**
     * Returns one product.
     *
     * @param sku the product code
     * @return the entry
     */
    @GetMapping("/{sku}")
    public CatalogEntry get(@PathVariable String sku) {
        return service.get(sku);
    }
}
