package dev.plainround.price.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;

/** Step 4's counter and step 3's retry bound, on small beans, with no network involved. */
class ResilienceAnnotationsTest {

    @Test
    void twentyConcurrentCallsAgainstALimitOfThreeNeverShowMoreThanThreeInFlight()
            throws Exception {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(Config.class)) {
            Slow slow = context.getBean(Slow.class);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> calls = new ArrayList<>();
                for (int call = 0; call < 20; call++) {
                    calls.add(
                            pool.submit(
                                    () -> {
                                        slow.blockingCall();
                                        return null;
                                    }));
                }
                for (Future<?> call : calls) {
                    call.get();
                }
            }

            assertThat(slow.maxInFlight()).isEqualTo(3);
        }
    }

    @Test
    void theRejectPolicyRefusesTheExcessAtOnceInsteadOfQueueing() throws Exception {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(Config.class)) {
            Slow slow = context.getBean(Slow.class);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger admitted = new AtomicInteger();

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> calls = new ArrayList<>();
                for (int call = 0; call < 6; call++) {
                    calls.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            slow.rejectingCall(release);
                                            admitted.incrementAndGet();
                                        } catch (InvocationRejectedException refused) {
                                            rejected.incrementAndGet();
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }));
                }
                while (rejected.get() < 4) {
                    Thread.sleep(10);
                }
                release.countDown();
                for (Future<?> call : calls) {
                    call.get();
                }
            }

            assertThat(admitted.get()).isEqualTo(2);
            assertThat(rejected.get()).isEqualTo(4);
        }
    }

    @Test
    void retryableStopsAfterTheConfiguredNumberOfRetriesAndOnlyForTheNamedException() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(Config.class)) {
            Flaky flaky = context.getBean(Flaky.class);

            assertThatThrownBy(flaky::alwaysUnavailable).isInstanceOf(IllegalStateException.class);
            assertThat(flaky.unavailableCalls()).as("1 original + 2 retries").isEqualTo(3);

            assertThatThrownBy(flaky::alwaysDifferent).isInstanceOf(IllegalArgumentException.class);
            assertThat(flaky.differentCalls()).as("not the named exception: no retry").isEqualTo(1);
        }
    }

    @Configuration
    @EnableResilientMethods
    static class Config {

        @Bean
        Slow slow() {
            return new Slow();
        }

        @Bean
        Flaky flaky() {
            return new Flaky();
        }
    }

    static class Slow {
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();

        // Read through a method: the bean is a proxy, and a field read on the proxy would see its
        // own empty copy.
        public int maxInFlight() {
            return maxInFlight.get();
        }

        @ConcurrencyLimit(3)
        public void blockingCall() throws InterruptedException {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
        public void rejectingCall(CountDownLatch release) throws InterruptedException {
            release.await();
        }
    }

    static class Flaky {
        private final AtomicInteger unavailableCalls = new AtomicInteger();
        private final AtomicInteger differentCalls = new AtomicInteger();

        public int unavailableCalls() {
            return unavailableCalls.get();
        }

        public int differentCalls() {
            return differentCalls.get();
        }

        @Retryable(
                includes = IllegalStateException.class,
                maxRetries = 2,
                delay = 1,
                jitter = 1,
                multiplier = 2.0)
        public void alwaysUnavailable() {
            unavailableCalls.incrementAndGet();
            throw new IllegalStateException("503");
        }

        @Retryable(includes = IllegalStateException.class, maxRetries = 2, delay = 1)
        public void alwaysDifferent() {
            differentCalls.incrementAndGet();
            throw new IllegalArgumentException("timeout");
        }
    }
}
