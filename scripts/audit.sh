#!/usr/bin/env bash
# Dependency audit (OSV-Scanner 2.7.0, pinned by digest): OSV-Scanner reads pom.xml (with the Spring Boot parent's managed versions) and reports
# known vulnerabilities. Fails on any finding that is not listed, with a reason, in osv-scanner.toml.
set -euo pipefail

cd "$(dirname "$0")/.."
IMAGE="${OSV_SCANNER_IMAGE:-ghcr.io/google/osv-scanner@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0}"
docker run --rm -v "$PWD:/src:ro" -w /src "$IMAGE" scan source --lockfile=pom.xml --config=osv-scanner.toml
