# Spring Framework resilience annotations (@Retryable, @ConcurrencyLimit)

## What it is

Spring Framework 7 includes two annotations for making calls safer, in the package `org.springframework.resilience.annotation`:

- `@Retryable` repeats a method call when it throws certain exceptions, with a delay that can grow and be randomized.
- `@ConcurrencyLimit` allows only a set number of calls to the method at the same time.

They are switched on by `@EnableResilientMethods` on a configuration class. This is Spring's own feature; the older separate "Spring Retry" library (`org.springframework.retry`) is not used.

## Why this repository uses it

The lesson's rules: retry only a 503, at most twice, with growing and jittered delays; never retry a timeout; allow only as many calls to the provider at once as it can sustain, and decide what happens to the rest.

## Where it appears

| Path | Role |
| --- | --- |
| `infrastructure/ResilienceConfig.java` | `@EnableResilientMethods` |
| `infrastructure/ProviderCall.java` | both annotations on `attempt(sku)` |
| `application.properties` | the numbers: retries, delay, multiplier, jitter, limit |
| `ResilienceAnnotationsTest.java` | the annotations on small test beans, no network |
| `PriceServiceBehaviorIT.java` | the same rules against a real provider |

## How it works underneath

1. Both annotations are applied by a **proxy** (see `spring-framework.md`). The call from another bean goes proxy → retry logic → limit logic → real method. A call from the same class skips all of it.
2. **`@Retryable`:** when the method throws an exception that matches `includes` (and not `excludes`), the interceptor waits for the delay and calls again, up to `maxRetries` more times. Then it rethrows the last exception. The delay starts at `delay`, is multiplied by `multiplier` after each retry (exponential backoff), is capped by `maxDelay`, and has a random addition up to `jitter` so that many callers do not retry at the same instant.
3. **`@ConcurrencyLimit`:** the interceptor counts the calls in progress. With the default policy `BLOCK` an extra call waits for a free slot. With `REJECT` an extra call fails at once with `InvocationRejectedException`.
4. `maxRetriesString`, `delayString` and similar attributes take a placeholder such as `"${price.provider.retry.max-retries:2}"`, resolved from the settings, so the numbers live in `application.properties`.
5. Only the exceptions named in `includes` are retried. A timeout is a different exception, so naming only the 503 type means timeouts are never retried, with no extra check to remember.

## Walkthrough of this repository's use

```java
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
public class ResilienceConfig {}
```

`@EnableResilientMethods` registers the processors that find the two annotations.

```java
@Retryable(
        includes = HttpServerErrorException.ServiceUnavailable.class,
        maxRetriesString = "${price.provider.retry.max-retries:2}",
        delayString = "${price.provider.retry.delay-ms:100}",
        multiplierString = "${price.provider.retry.multiplier:2.0}",
        jitterString = "${price.provider.retry.jitter-ms:50}",
        maxDelayString = "${price.provider.retry.max-delay-ms:1000}")
@ConcurrencyLimit(
        limitString = "${price.provider.max-concurrent-calls:10}",
        policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
public Quote attempt(String sku) {
```

- `includes = HttpServerErrorException.ServiceUnavailable.class`: retry only a 503.
- `maxRetriesString = "${price.provider.retry.max-retries:2}"`: two retries, so at most **three** provider calls per request. `delayString` 100 ms, `multiplierString` 2.0 (so 100 ms then 200 ms), `jitterString` up to 50 ms, `maxDelayString` 1000 ms.
- `@ConcurrencyLimit(limitString = "${price.provider.max-concurrent-calls:10}", policy = ConcurrencyLimit.ThrottlePolicy.REJECT)`: at most 10 provider calls at once; call 11 fails immediately. Waiting would make the latency unbounded while the provider hangs (see `resilience-patterns.md` and ADR 0002).

The settings:

```properties
price.provider.retry.max-retries=2
price.provider.retry.delay-ms=100
price.provider.retry.multiplier=2.0
price.provider.retry.jitter-ms=50
price.provider.retry.max-delay-ms=1000

# At most this many provider calls in flight; the rest are refused at once (policy REJECT), sized to the provider.
price.provider.max-concurrent-calls=${PRICE_PROVIDER_MAX_CONCURRENT_CALLS:10}
```

The tests on a small bean prove the behavior without a network:

```java
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
```

`Slow` has a method with `@ConcurrencyLimit(3)` (block policy) and one with `limit = 2, policy = REJECT`. The first test starts 20 calls on virtual threads and asserts the method's own counter never saw more than 3 at once. The second holds two calls open and shows the other four are rejected immediately. A note in the class: the test reads the counter through a method, because the bean is a proxy and a field read on the proxy would see its own empty copy.

Retries are counted by an independent witness. `PriceServiceBehaviorIT.retriesAtMostThreeCallsTotalOn503` switches the provider to `fail503` and asserts the **provider's own counter** reads 3 for one price request; the timeout test asserts it reads 1.

## Try it yourself

1. `./mvnw test -pl price-service -Dtest=ResilienceAnnotationsTest` runs the annotations alone.
2. After `make up`: `make run` prints the provider's call counts for 503, hang and slow, and its `maxInFlight` under a burst.
3. In a scratch copy change `policy = REJECT` to the default and run `PriceServiceBehaviorIT`: the burst test no longer sees refused requests.

## Mistakes people make

- Naming a broad exception in `@Retryable` (for example `RestClientException`): it retries timeouts and connection failures too and creates a retry storm.
- Calling an annotated method from the same class.
- A limit larger than the load: the test then proves nothing.
- Retrying a non-idempotent call (one that changes data) without protection.

## Check yourself

1. How many provider calls can one price request make at most? *Three: one plus two retries.*
2. What is jitter for? *To spread the retries of many callers in time.*
3. What does `REJECT` do to the eleventh call? *Fails it immediately.*
4. Why name only the 503 exception? *Timeouts are then never retried.*
5. Why does the annotation not work on a self-call? *The proxy is skipped.*

## Further reading

- Spring Framework 7.0.10, "Resilience Features": https://docs.spring.io/spring-framework/reference/7.0.10/core/resilience.html
