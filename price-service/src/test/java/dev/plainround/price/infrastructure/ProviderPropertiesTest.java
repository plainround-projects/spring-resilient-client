package dev.plainround.price.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class ProviderPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                    .withUserConfiguration(Config.class)
                    .withPropertyValues("price.provider.base-url=http://localhost:9");

    @Test
    void defaultsAreOneSecondToConnectAndTwoToRead() {
        runner.run(
                context -> {
                    ProviderProperties properties = context.getBean(ProviderProperties.class);
                    assertThat(properties.connectTimeout().toMillis()).isEqualTo(1000);
                    assertThat(properties.readTimeout().toMillis()).isEqualTo(2000);
                    assertThat(context.getBean(CacheProperties.class).ttl().toSeconds())
                            .isEqualTo(60);
                });
    }

    @Test
    void aZeroOrNegativeTimeoutStopsTheApplicationAtStartup() {
        runner.withPropertyValues("price.provider.read-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("price.provider.connect-timeout=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMissingBaseUrlOrAnAbsurdCacheBoundStopsTheApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("price.cache.max-entries=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties({ProviderProperties.class, CacheProperties.class})
    static class Config {}
}
