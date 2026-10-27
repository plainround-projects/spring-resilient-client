#!/usr/bin/env bash
# The lesson's step 6 table: p50, p99 and error rate for every provider mode, before (price.protection=none:
# no timeouts, retries, limit or cache) and after (price.protection=full), at the same load. The provider's own
# call count is the independent witness. Writes docs/benchmark.md.
# Usage: scripts/benchmark.sh [price-url] [provider-url]   (env: RPS=100 SECONDS_PER_RUN=20)
set -euo pipefail

PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
RPS="${RPS:-100}"
SECONDS_PER_RUN="${SECONDS_PER_RUN:-20}"
LOADGEN="java -jar loadgen/target/loadgen-0.1.0.jar"
OUT=docs/benchmark.md

mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
wait_ready() { until curl -fsS "$PRICE/actuator/health/readiness" 2>/dev/null | jq -e '.status == "UP"' >/dev/null; do sleep 2; done; }

{
  echo "# Benchmark: before and after"
  echo
  echo "$RPS requests a second for ${SECONDS_PER_RUN} s per run against 10 SKUs, plus 10 requests a second to the catalog endpoint (\`/skus/sku-1\`), which never calls the provider. Slow mode answers after 5 s, hang mode after 10 s. 'Before' is \`price.protection=none\`; 'after' is the service this project builds. Run on $(date -u +%Y-%m-%d) on $(uname -sm); numbers depend on the machine."
  echo
  echo "| protection | provider mode | p50 (ms) | p99 (ms) | error rate | stale answers | provider calls | catalog p99 (ms) |"
  echo "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"
} > "$OUT"

for protection in none full; do
  PRICE_PROTECTION="$protection" docker compose up -d --force-recreate --no-deps price-service >/dev/null
  wait_ready
  for provider_mode in normal "slow delayMs=5000" fail503 hang; do
    mode normal
    for sku in $(seq 0 9); do curl -fsS "$PRICE/price/sku-$sku" >/dev/null; done   # warm the cache (full only)
    mode $provider_mode
    report="$($LOADGEN --target "$PRICE/price/{sku}" --skus 10 --rps "$RPS" --seconds "$SECONDS_PER_RUN" \
      --side "$PRICE/skus/sku-1" --side-rps 10)"
    calls="$(curl -fsS "$PROVIDER/admin/stats" | jq .callsReceived)"
    price="$(echo "$report" | jq -s '.[] | select(.name == "price")')"
    side="$(echo "$report" | jq -s '.[] | select(.name == "side")')"
    printf '| %s | %s | %s | %s | %s%% | %s | %s | %s |\n' "$protection" "${provider_mode%% *}" \
      "$(jq -r .p50Ms <<<"$price")" "$(jq -r .p99Ms <<<"$price")" \
      "$(jq -r '.errorRate * 100 | round' <<<"$price")" "$(jq -r .stale <<<"$price")" "$calls" "$(jq -r .p99Ms <<<"$side")" | tee -a "$OUT"
  done
done
mode normal
PRICE_PROTECTION=full docker compose up -d --force-recreate --no-deps price-service >/dev/null
echo; echo "written to $OUT"
