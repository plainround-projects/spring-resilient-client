# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Drawn architecture and flow figures in `docs/diagrams/`, light and dark, shown in the README.

## [0.1.0] - 2026-10-27

### Added

- `provider`: a plain JDK HttpServer with the modes normal, slow, 503 and hang, a "fail the next N calls" switch, its own counters (calls received, in flight, most in flight), `/admin/*`, `/metrics`, JSON logs and graceful stop.
- `price-service`: a price API over `RestClient` with a connect and a read timeout on every call, `@Retryable` for 503 only (two retries, exponential backoff with jitter), `@ConcurrencyLimit` with the reject policy, a 60 second quote cache served stale with a label, problem documents for 504 and 503, virtual threads, Micrometer metrics and OpenTelemetry traces over OTLP, an `X-Trace-Id` header, Actuator probes and graceful shutdown.
- `loadgen`: an open-loop load generator reporting p50, p99, error rate and stale share.
- A Grafana stack in `docker-compose.yml`, `make benchmark` (before and after, every provider mode) and `make traces` (a retried trace and a stale trace from Tempo).
- Layered packages with ArchUnit rules (dependency rule, no self-invocation of proxied methods), Spotless, Checkstyle, JaCoCo gates, multi-stage non-root Dockerfiles, GitHub Actions CI with a dependency audit.
