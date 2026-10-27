package dev.plainround.price.domain;

/** The catalog has no product with the requested code. */
public class SkuNotFound extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param sku the code that matched nothing
     */
    public SkuNotFound(String sku) {
        super("no product with sku " + sku);
    }
}
