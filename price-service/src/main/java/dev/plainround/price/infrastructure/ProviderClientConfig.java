package dev.plainround.price.infrastructure;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the RestClient for the provider, with a connect timeout and a read timeout on every call.
 */
@Configuration(proxyBeanMethods = false)
public class ProviderClientConfig {

    /**
     * Creates the RestClient used for provider calls. It starts from Spring Boot's builder so
     * metrics and traces cover every call, and sets both timeouts explicitly.
     *
     * @param builder Spring Boot's RestClient builder, with observation and message converters
     * @param properties the provider address and timeouts
     * @return the client
     */
    @Bean
    @ConditionalOnProperty(name = "price.protection", havingValue = "full", matchIfMissing = true)
    public RestClient providerRestClient(
            RestClient.Builder builder, ProviderProperties properties) {
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    /**
     * Creates the unprotected client of the benchmark's "before" run: no connect timeout, no read
     * timeout.
     *
     * @param builder Spring Boot's RestClient builder
     * @param properties the provider address
     * @return the client
     */
    @Bean("providerRestClient")
    @ConditionalOnProperty(name = "price.protection", havingValue = "none")
    public RestClient unprotectedRestClient(
            RestClient.Builder builder, ProviderProperties properties) {
        return builder.baseUrl(properties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }
}
