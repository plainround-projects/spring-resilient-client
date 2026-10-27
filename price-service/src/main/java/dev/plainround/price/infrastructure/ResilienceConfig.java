package dev.plainround.price.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Switches on Spring Framework's own resilience annotations ({@code @Retryable},
 * {@code @ConcurrencyLimit}), the package {@code org.springframework.resilience.annotation}, not
 * the older separate Spring Retry library.
 */
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
public class ResilienceConfig {}
