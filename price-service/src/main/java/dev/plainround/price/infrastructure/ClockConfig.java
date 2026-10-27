package dev.plainround.price.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one place the real clock is created; tests replace this bean. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Provides the system clock in UTC.
     *
     * @return the clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
