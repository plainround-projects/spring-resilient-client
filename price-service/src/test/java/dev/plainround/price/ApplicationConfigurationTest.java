package dev.plainround.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The resilience settings live in one file; this test fails if someone loosens them by accident. No
 * Spring context.
 */
class ApplicationConfigurationTest {

    private static Properties main() throws IOException {
        Properties properties = new Properties();
        try (Reader reader =
                Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void everyProviderCallHasBothTimeoutsAndTheRetryRulesFromTheLesson() throws IOException {
        Properties properties = main();

        assertThat(properties.getProperty("price.provider.connect-timeout")).isEqualTo("1s");
        assertThat(properties.getProperty("price.provider.read-timeout")).isEqualTo("2s");
        assertThat(properties.getProperty("price.provider.retry.max-retries")).isEqualTo("2");
        assertThat(properties.getProperty("price.cache.ttl")).isEqualTo("60s");
        assertThat(properties.getProperty("price.protection")).contains("full");
    }

    @Test
    void virtualThreadsJsonLogsGracefulShutdownAndFullTracingAreOn() throws IOException {
        Properties properties = main();

        assertThat(properties.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
        assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(properties.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
    }
}
