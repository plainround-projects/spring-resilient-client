package dev.plainround.price.domain;

/** The provider failed and there is no recent quote to fall back on. */
public class NoPriceAvailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProviderFailure failure;

    /**
     * Creates the error.
     *
     * @param sku the product code that could not be priced
     * @param failure why the provider could not answer
     */
    public NoPriceAvailable(String sku, ProviderFailure failure) {
        super("no price available for " + sku, failure);
        this.failure = failure;
    }

    /**
     * Returns why the provider could not answer.
     *
     * @return the provider failure
     */
    public ProviderFailure failure() {
        return failure;
    }
}
