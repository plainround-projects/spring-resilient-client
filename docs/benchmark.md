# Benchmark: before and after

**Pending capacity.** The table is written by `make benchmark` (it needs the Docker stack from `make up`), and the machine this repository was built on had no working Docker that night. Run `make up && make benchmark` and this file is overwritten with the measured table: p50, p99, error rate, stale answers, provider calls and the catalog endpoint's p99, for `normal`, `slow`, `fail503` and `hang`, with `price.protection=none` (before) and `full` (after).

What the tests already prove without Docker, with the provider's own counters as the witness (`PriceServiceBehaviorIT`):

| Provider mode | One price request makes this many provider calls | Answer |
| --- | --- | --- |
| normal | 1 | 200, `stale: false` |
| fail503 | 3 (1 + 2 retries) | 200 `stale: true` if priced within 60 s, else 503 |
| hang (test read timeout 500 ms) | 1 (a timeout is not retried) | 200 stale if remembered, else 504 in about the read timeout |
| slow past the read timeout | 1 | same as hang |
| 20 concurrent requests, provider slow, limit 3 | the provider's own `maxInFlight` never exceeds 3 | the excess is refused at once (503 or stale); the catalog endpoint answers in under 300 ms meanwhile |
