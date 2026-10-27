package dev.plainround.price.infrastructure;

import dev.plainround.price.domain.ProviderBusy;
import dev.plainround.price.domain.ProviderFailure;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.ProviderUnavailable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns what the HTTP client and the resilience proxies throw into the domain's three named
 * provider failures.
 */
final class ProviderErrors {

    private ProviderErrors() {}

    /**
     * Names a failure of a provider call.
     *
     * @param exception what the call threw
     * @return the domain failure, or the exception itself when it already is one
     */
    static ProviderFailure translate(RuntimeException exception) {
        return switch (exception) {
            case ProviderFailure failure -> failure;
            case InvocationRejectedException rejected -> new ProviderBusy(rejected);
            case ResourceAccessException ioProblem when isTimeout(ioProblem) ->
                    new ProviderTimeout(ioProblem);
            case ResourceAccessException ioProblem ->
                    new ProviderUnavailable("the provider could not be reached", ioProblem);
            case HttpServerErrorException serverError ->
                    new ProviderUnavailable(
                            "the provider answered " + serverError.getStatusCode().value(),
                            serverError);
            default -> new ProviderUnavailable("unexpected answer from the provider", exception);
        };
    }

    /**
     * Gives a failure a short name for metrics.
     *
     * @param failure the failure
     * @return {@code timeout}, {@code busy} or {@code unavailable}
     */
    static String reason(ProviderFailure failure) {
        return switch (failure) {
            case ProviderTimeout timeout -> "timeout";
            case ProviderBusy busy -> "busy";
            case ProviderUnavailable unavailable -> "unavailable";
        };
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause instanceof IOException
                    && String.valueOf(cause.getMessage()).contains("timed out")) {
                return true;
            }
        }
        return false;
    }
}
