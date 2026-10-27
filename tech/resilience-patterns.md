# Resilience patterns (timeouts, bounded retries, load shedding, stale cache)

## What it is

A **dependency** is another service your service calls. A **sick** dependency is slow, failing or silent. **Resilience** means your service stays useful while that happens. Four ideas cover most of it:

- **Timeout:** never wait forever. Decide the longest a call may take.
- **Bounded retry:** try again only for failures likely to clear up quickly, only a few times, with growing and randomized delays (**exponential backoff with jitter**).
- **Load shedding (concurrency limit):** allow only as many calls at once as the dependency can take, and refuse the rest at once.
- **Graceful degradation (stale answer):** when the dependency fails, answer with the last good result and say it is old.

## Why this repository uses it

This is the lesson: "make a Spring service survive a slow and failing dependency". The goal is not zero errors. It is **bounded latency**, **no retry storm**, and a **clear signal when an answer is stale**.

## Where it appears

| Idea | Where in the code |
| --- | --- |
| Timeout | `ProviderClientConfig` (1 s connect, 2 s read) |
| Bounded retry | `@Retryable` on `ProviderCall.attempt` |
| Load shedding | `@ConcurrencyLimit(policy = REJECT)` on the same method |
| Stale answer | `PriceService.fallback`, `InMemoryQuoteCache` |
| Telling failures apart | `ProviderErrors`, the sealed `ProviderFailure` types, `ProviderExceptionHandler` |
| Proof | `PriceServiceBehaviorIT`, `make run`, `make benchmark` |

## How it works underneath

1. **Why timeouts come first.** Without one, each request to a hung provider holds a thread (or a virtual thread) and a connection until the provider answers, which may be never. Callers pile up. With a 2 second read timeout, the worst a request can cost is about 2 seconds.
2. **Why only a 503 is retried.** A 503 means the provider decided quickly that it cannot serve right now; it often clears within moments. A timeout means the provider is already too slow; sending the same request again adds load to a provider that is struggling. Retrying everything multiplies traffic exactly when the provider can least take it: a **retry storm**.
3. **Why jitter.** If 100 callers fail at the same moment and all retry after exactly 100 ms, the provider gets 100 requests at once again. Random extra delay spreads them out.
4. **Why a limit and why reject.** A timeout bounds one call; it says nothing about how many calls are waiting at once. With a hung provider and a 2 second timeout, 10 slots serve 5 calls a second. At 100 requests a second, a waiting queue would grow without limit and so would latency. Refusing the excess at once keeps every answer fast, and a stale answer from the cache can still serve the caller.
5. **Why a stale answer must be labeled.** A price from a minute ago may be fine for a product page and wrong for a payment. The response carries `stale: true` and `ageSeconds`, so the caller decides. A SKU that was never priced has nothing to fall back on, and the answer is an error (503, or 504 if the cause was a timeout).
6. **Why measure.** A claim such as "the provider saw at most three calls" is checked against the provider's own counter, not the client's report of itself.

## Walkthrough of this repository's use

**The decision logic.**

```java
public PriceResult price(String sku) {
    try {
        Quote quote = source.fetch(sku);
        cache.put(sku, quote);
        return PriceResult.fresh(quote);
    } catch (ProviderFailure failure) {
        return fallback(sku, failure);
    }
}
```

Try the provider; remember a good quote; on any `ProviderFailure` fall back. The exception types say why it failed.

```java
private PriceResult fallback(String sku, ProviderFailure failure) {
    LOG.warn("provider failed sku={} reason={}", sku, failure.getClass().getSimpleName());
    return cache.get(sku)
            .map(quote -> PriceResult.stale(quote, clock.instant()))
            .orElseThrow(() -> new NoPriceAvailable(sku, failure));
}
```

`cache.get(sku)` returns a quote only if it is still young enough; `.map(...)` turns it into a stale result with its age; `.orElseThrow(...)` raises `NoPriceAvailable` carrying the original failure so the web layer can choose 504 or 503.

**The cache.**

```java
public synchronized Optional<Quote> get(String sku) {
    Optional<Quote> young = Optional.ofNullable(quotes.get(sku)).filter(this::isYoungEnough);
    if (young.isEmpty()) {
        quotes.remove(sku);
    }
    meters.counter("price.cache.lookups", "result", young.isPresent() ? "hit" : "miss")
            .increment();
    return young;
}
```

A quote is valid until `ttl` after it was **written**. The check `!quote.fetchedAt().plus(ttl).isBefore(clock.instant())` uses the write time stored in the quote, so reading it often does not keep it alive. Expired entries are removed on the lookup and the hit or miss is counted. The map itself is a bounded `LinkedHashMap` that drops its oldest entry when full, so a stream of new SKUs cannot grow memory without limit.

**The two variants.** `price.protection=full` builds the service above. `price.protection=none` swaps in `UnprotectedQuoteSource` (no timeouts, retries or limit) and `NoQuoteCache`, so the benchmark can show the same load with and without protection.

**The web answer.**

```java
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
```

## Try it yourself

1. `make up`, then `make run`: step 3 prints the provider's call counts for a 503 (3), a hang (1) and a slow answer (1).
2. `curl -X PUT 'localhost:18103/admin/mode?mode=fail503'; curl -s localhost:18104/price/sku-1` after pricing `sku-1` once in normal mode: `stale: true` with its age.
3. `make benchmark` writes the before/after table to `docs/benchmark.md`.
4. `./mvnw verify -pl provider,price-service` proves each rule against an in-process provider.

## Mistakes people make

- Timeouts longer than the caller's own timeout.
- Retrying inside every layer: three layers with three tries each make 27 calls.
- Retrying a request that changes data without an idempotency key.
- A cache with no label: the caller cannot tell a fresh price from an old one.
- Measuring only the average: the 99th percentile is where the waiting shows.

## Check yourself

1. What does a timeout bound, and what does it not bound? *One call's duration, not the number of calls waiting.*
2. Why is a timeout not retried? *It adds load to an already struggling provider.*
3. What is a retry storm? *Retries multiplying traffic during an outage.*
4. Why reject instead of wait? *Waiting makes latency unbounded.*
5. When is a SKU answered with an error instead of a stale price? *When nothing recent is remembered.*

## Further reading

- Spring Framework 7.0.10, "Resilience Features": https://docs.spring.io/spring-framework/reference/7.0.10/core/resilience.html
- Exponential backoff and jitter (AWS Architecture Blog): https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/
