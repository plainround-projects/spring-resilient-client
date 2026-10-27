package dev.plainround.price.domain;

/**
 * The provider could not give a price right now. The three subtypes say why, and only one of them
 * is retried.
 */
public abstract sealed class ProviderFailure extends RuntimeException
        permits ProviderTimeout, ProviderUnavailable, ProviderBusy {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param message what went wrong
     * @param cause the underlying error, or null
     */
    protected ProviderFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
