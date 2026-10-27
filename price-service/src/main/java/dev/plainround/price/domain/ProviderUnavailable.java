package dev.plainround.price.domain;

/** The provider said it cannot serve (503), could not be reached, or sent nothing usable. */
public final class ProviderUnavailable extends ProviderFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param message what went wrong
     */
    public ProviderUnavailable(String message) {
        super(message, null);
    }

    /**
     * Creates the failure with its cause.
     *
     * @param message what went wrong
     * @param cause the underlying error
     */
    public ProviderUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
