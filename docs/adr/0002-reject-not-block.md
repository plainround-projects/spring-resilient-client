# 0002. The concurrency limit rejects the excess instead of making it wait

Date: 2026-10-27. Status: accepted.

## Context

The lesson asks to size `@ConcurrencyLimit` from the provider's capacity and to "decide what happens to the excess: wait briefly or fail fast". Framework 7 offers both policies: `BLOCK` (the default) waits for a slot with no bound; `REJECT` throws `InvocationRejectedException` immediately.

## Decision

`@ConcurrencyLimit(limitString = "${price.provider.max-concurrent-calls}", policy = REJECT)`, default limit 10. A rejected call becomes `ProviderBusy`, which the use case treats like any provider failure: serve the last good quote, labeled stale, or answer 503 with `Retry-After`.

## Alternatives considered

- `BLOCK` with a short wait: not available as a policy; the framework offers no timeout on the wait. With the provider hanging, every call holds a slot for the full read timeout (2 seconds), so 10 slots serve 5 calls a second; at 100 requests a second the queue of waiters grows without bound and p99 with it. That contradicts the project's goal of bounded latency.
- A hand-written semaphore with `tryAcquire(timeout)`: possible, but duplicates what the framework provides.

## Consequences

- p99 stays bounded in every provider mode (see `docs/benchmark.md` once the benchmark has run).
- When the provider is healthy but slow-ish and a burst arrives, some requests get a stale answer or a 503 that a patient wait would have turned into a fresh price. If that matters, raise the limit; the provider's `maxInFlight` counter shows what it can take.
