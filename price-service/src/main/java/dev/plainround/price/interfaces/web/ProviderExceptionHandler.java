package dev.plainround.price.interfaces.web;

import dev.plainround.price.domain.NoPriceAvailable;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.SkuNotFound;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place errors become {@code application/problem+json}. A SKU that could not be priced is a
 * 504 when the provider timed out and a 503 (with {@code Retry-After}) for every other provider
 * failure; the two mean different things to a caller and are never merged.
 */
@RestControllerAdvice
public class ProviderExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderExceptionHandler.class);
    private static final String PROBLEM_URN = "urn:plainround:problem:";

    /**
     * Maps "provider failed and nothing recent is remembered" to 504 or 503.
     *
     * @param exception the error
     * @return the problem
     */
    @ExceptionHandler(NoPriceAvailable.class)
    public ResponseEntity<ProblemDetail> handleNoPrice(NoPriceAvailable exception) {
        if (exception.failure() instanceof ProviderTimeout) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(
                            problem(
                                    HttpStatus.GATEWAY_TIMEOUT,
                                    "provider-timeout",
                                    "Provider timed out",
                                    exception.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(
                        problem(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "provider-unavailable",
                                "Provider unavailable",
                                exception.getMessage()));
    }

    /**
     * Maps an unknown catalog code to 404.
     *
     * @param exception the error
     * @return the problem
     */
    @ExceptionHandler(SkuNotFound.class)
    public ProblemDetail handleSkuNotFound(SkuNotFound exception) {
        return problem(
                HttpStatus.NOT_FOUND, "sku-not-found", "Product not found", exception.getMessage());
    }

    /**
     * Maps anything unexpected to 500 without leaking its message.
     *
     * @param exception the error
     * @return the problem
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        LOG.error("unexpected error", exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal error",
                "The request failed.");
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<Map<String, String>> errors =
                exception.getParameterValidationResults().stream()
                        .flatMap(result -> errorsOf(result).stream())
                        .toList();
        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "validation-failed",
                        "Validation failed",
                        "The request failed validation.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static List<Map<String, String>> errorsOf(ParameterValidationResult result) {
        String parameter = String.valueOf(result.getMethodParameter().getParameterName());
        return result.getResolvableErrors().stream()
                .map(
                        error ->
                                Map.of(
                                        "field",
                                        parameter,
                                        "message",
                                        String.valueOf(error.getDefaultMessage())))
                .toList();
    }

    private static ProblemDetail problem(
            HttpStatus status, String name, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_URN + name));
        problem.setTitle(title);
        return problem;
    }
}
