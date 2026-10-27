package dev.plainround.price.interfaces.web;

import dev.plainround.price.application.PriceService;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of pricing. */
@RestController
public class PriceController {

    private final PriceService service;

    /**
     * Wires the controller.
     *
     * @param service the pricing use case
     */
    public PriceController(PriceService service) {
        this.service = service;
    }

    /**
     * Prices a SKU, fresh when the provider answers and labeled stale when it does not.
     *
     * @param sku the product code, letters, digits, dot, dash and underscore, up to 64 characters
     * @return the price
     */
    @GetMapping("/price/{sku}")
    public PriceResponse price(
            @PathVariable
                    @Pattern(
                            regexp = "[A-Za-z0-9._-]{1,64}",
                            message =
                                    "sku must be 1 to 64 letters, digits, dots, dashes or"
                                            + " underscores")
                    String sku) {
        return PriceResponse.from(service.price(sku));
    }
}
