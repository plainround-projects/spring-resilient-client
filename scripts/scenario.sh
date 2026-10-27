#!/usr/bin/env bash
# The lesson's steps 1, 3, 4 and 5 against the running stack, with the provider's own counters as the witness.
# Usage: scripts/scenario.sh [price-url] [provider-url]
set -euo pipefail

PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
LOADGEN="java -jar loadgen/target/loadgen-0.1.0.jar"

heading() { printf '\n== %s\n' "$1"; }
mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
calls() { curl -fsS "$PROVIDER/admin/stats" | jq -r .callsReceived; }
maxflight() { curl -fsS "$PROVIDER/admin/stats" | jq -r .maxInFlight; }

heading "Step 1: provider in hang mode; a never-priced SKU fails in about the 2 s read timeout with a 504 problem"
mode hang
curl -sS -o /tmp/price-hang.json -w 'HTTP %{http_code} in %{time_total}s\n' "$PRICE/price/sku-hang-demo"
jq -c . /tmp/price-hang.json

heading "Step 2: baseline against the healthy provider, 100 requests a second for 20 s (p50, p99, error rate)"
mode normal
$LOADGEN --target "$PRICE/price/{sku}" --skus 10 --rps 100 --seconds 20 --side "$PRICE/skus/sku-1" --side-rps 10 | jq -c .

heading "Step 3: calls the provider receives for ONE price request"
mode fail503
curl -sS -o /dev/null -w 'fail503: HTTP %{http_code}   ' "$PRICE/price/sku-retry-demo"; echo "provider calls: $(calls)   (1 original + 2 retries)"
mode hang
curl -sS -o /dev/null -w 'hang:    HTTP %{http_code}   ' "$PRICE/price/sku-timeout-demo"; echo "provider calls: $(calls)   (a timeout is not retried)"
mode slow delayMs=5000
curl -sS -o /dev/null -w 'slow:    HTTP %{http_code}   ' "$PRICE/price/sku-slow-demo"; echo "provider calls: $(calls)   (a timeout is not retried)"

heading "Step 4: 60 concurrent requests for 60 different SKUs against a slow provider; the provider's own maximum in flight"
mode slow delayMs=3000
seq 1 60 | xargs -P 60 -I{} curl -sS -o /dev/null -w '%{http_code}\n' "$PRICE/price/sku-burst-{}" | sort | uniq -c | sed 's/^/  HTTP /'
echo "provider max in flight: $(maxflight)   (the limit is PRICE_PROVIDER_MAX_CONCURRENT_CALLS, default 10)"
curl -sS -o /dev/null -w 'the catalog endpoint during a slow provider: HTTP %{http_code} in %{time_total}s\n' "$PRICE/skus/sku-1"

heading "Step 5: stale with a label"
mode normal
curl -fsS "$PRICE/price/sku-1" | jq -c .
mode fail503
echo "provider down, sku-1 priced a moment ago:"; curl -fsS "$PRICE/price/sku-1" | jq -c .
echo "provider down, never priced:"; curl -sS -w '\nHTTP %{http_code}\n' "$PRICE/price/sku-never-priced"
mode normal
