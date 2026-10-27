package dev.plainround.price.domain;

/**
 * The provider did not answer within the read or connect timeout. Never retried: it only adds load.
 */
public final class ProviderTimeout extends ProviderFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param cause the timeout from the HTTP client
     */
    public ProviderTimeout(Throwable cause) {
        super("the provider did not answer in time", cause);
    }
}
