package dev.plainround.price.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plainround.price.domain.ProviderBusy;
import dev.plainround.price.domain.ProviderFailure;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.ProviderUnavailable;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class ProviderErrorsTest {

    @Test
    void aTimeoutInsideAnIoErrorIsAProviderTimeout() {
        ResourceAccessException readTimeout =
                new ResourceAccessException(
                        "I/O error", new HttpTimeoutException("request timed out"));
        ResourceAccessException socketTimeout =
                new ResourceAccessException(
                        "I/O error", new SocketTimeoutException("Read timed out"));

        assertThat(ProviderErrors.translate(readTimeout)).isInstanceOf(ProviderTimeout.class);
        assertThat(ProviderErrors.translate(socketTimeout)).isInstanceOf(ProviderTimeout.class);
    }

    @Test
    void aRefusedConnectionIsUnavailableNotATimeout() {
        ResourceAccessException refused =
                new ResourceAccessException(
                        "I/O error", new ConnectException("Connection refused"));

        assertThat(ProviderErrors.translate(refused)).isInstanceOf(ProviderUnavailable.class);
    }

    @Test
    void aRejectionByTheConcurrencyLimitIsBusy() {
        ProviderFailure failure =
                ProviderErrors.translate(new InvocationRejectedException("limit reached", this));

        assertThat(failure).isInstanceOf(ProviderBusy.class);
        assertThat(ProviderErrors.reason(failure)).isEqualTo("busy");
    }

    @Test
    void aServerErrorAndAnythingElseAreUnavailable() {
        HttpServerErrorException serverError =
                HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "unavailable",
                        new HttpHeaders(),
                        new byte[0],
                        null);
        HttpClientErrorException clientError =
                HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "nf", new HttpHeaders(), new byte[0], null);

        assertThat(ProviderErrors.translate(serverError)).hasMessageContaining("503");
        assertThat(ProviderErrors.translate(clientError)).isInstanceOf(ProviderUnavailable.class);
        assertThat(ProviderErrors.reason(ProviderErrors.translate(serverError)))
                .isEqualTo("unavailable");
    }

    @Test
    void aDomainFailurePassesThroughUnchanged() {
        ProviderTimeout timeout = new ProviderTimeout(new RuntimeException("slow"));

        assertThat(ProviderErrors.translate(timeout)).isSameAs(timeout);
        assertThat(ProviderErrors.reason(timeout)).isEqualTo("timeout");
    }
}
