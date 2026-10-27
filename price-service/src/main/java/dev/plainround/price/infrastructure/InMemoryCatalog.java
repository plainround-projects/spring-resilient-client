package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.Catalog;
import dev.plainround.price.domain.CatalogEntry;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Ten fixed products, {@code sku-0} to {@code sku-9}: the catalog the shop's other endpoints serve.
 */
@Component
public class InMemoryCatalog implements Catalog {

    private final List<CatalogEntry> entries =
            IntStream.range(0, 10)
                    .mapToObj(index -> new CatalogEntry("sku-" + index, "Item " + index))
                    .toList();

    @Override
    public List<CatalogEntry> all() {
        return entries;
    }

    @Override
    public Optional<CatalogEntry> find(String sku) {
        return entries.stream().filter(entry -> entry.sku().equals(sku)).findFirst();
    }
}
