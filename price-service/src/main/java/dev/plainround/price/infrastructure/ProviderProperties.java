package dev.plainround.price.infrastructure;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the provider is and how long a call may take. Both timeouts are mandatory and positive:
 * there is no way to configure a call that waits forever.
 *
 * @param baseUrl the provider's base URL
 * @param connectTimeout how long opening the connection may take
 * @param readTimeout how long waiting for the answer may take once connected
 */
@Validated
@ConfigurationProperties("price.provider")
public record ProviderProperties(
        @NotBlank String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout) {

    /**
     * Checks that both timeouts are positive.
     *
     * @return true when neither timeout is zero or negative
     */
    @AssertTrue(message = "connect-timeout and read-timeout must be positive")
    public boolean isTimeoutsPositive() {
        return connectTimeout.isPositive() && readTimeout.isPositive();
    }
}
