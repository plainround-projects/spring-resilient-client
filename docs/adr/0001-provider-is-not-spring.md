# 0001. The fake provider is a plain JDK HttpServer, not a Spring application

Date: 2026-10-27. Status: accepted.

## Context

The lesson says "two Spring Boot applications" in its summary, and in its starting point says the fake provider is "deliberately not a Spring app: a plain `HttpServer` with a mode you flip at runtime and a counter of calls actually received". The two sentences disagree.

## Decision

The starting point wins. `provider` is a plain Java module with no framework: a JDK `HttpServer` on virtual threads, a mode switch, its own counters (calls received, in flight, most in flight) and a Prometheus-format `/metrics`. The price service is the only Spring Boot application.

## Alternatives considered

- A Spring Boot provider: its counters would live in the same kind of framework as the client, and the lesson's point is that the witness is independent of the thing it observes.

## Consequences

- Retry and concurrency claims are checked against a count the client never touched.
- The provider has no OpenTelemetry instrumentation; its traces are not exported. The price service's client spans show each provider call, and the provider's counters are scraped from `/metrics` or read from `/admin/stats`.
