package dev.plainround.price.domain;

/**
 * Too many calls to the provider are already in flight; this one was refused at once instead of
 * queueing.
 */
public final class ProviderBusy extends ProviderFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param cause the rejection from the concurrency limit
     */
    public ProviderBusy(Throwable cause) {
        super("too many calls to the provider are in flight", cause);
    }
}
