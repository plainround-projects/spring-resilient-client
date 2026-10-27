#!/usr/bin/env bash
# The lesson's step 6 trace evidence, as text instead of screenshots: one trace of a request that retried and
# succeeded, one of a request served stale after the provider failed, one of a request refused by the concurrency
# limit (no provider call at all). Each trace is fetched from Tempo by the id the service returned in X-Trace-Id.
# Usage: scripts/capture-traces.sh [price-url] [provider-url] [tempo-url]
set -euo pipefail

PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
TEMPO="${3:-http://localhost:${TEMPO_PORT:-13200}}"
OUT=docs/evidence
mkdir -p "$OUT"

mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
trace_id() { curl -sS -D - -o /dev/null "$PRICE/price/$1" | tr -d '\r' | awk -F': ' 'tolower($1) == "x-trace-id" { print $2 }'; }

summarize() { # trace id -> one line per span: name, duration in ms, status
  local id="$1" attempts=0
  until [ "$attempts" -ge 15 ]; do
    if curl -fsS "$TEMPO/api/traces/$id" -o "$OUT/$2.json" 2>/dev/null; then break; fi
    attempts=$((attempts + 1)); sleep 2
  done
  jq -r '[.. | objects | select(has("spans")) | .spans[]?] | sort_by(.startTimeUnixNano | tonumber)
         | .[] | "\(.name)\t\(((.endTimeUnixNano | tonumber) - (.startTimeUnixNano | tonumber)) / 1000000 | floor) ms"' \
    "$OUT/$2.json" > "$OUT/$2.txt"
  echo "== $2 (trace $id)"; cat "$OUT/$2.txt"
}

mode normal; curl -fsS "$PRICE/price/sku-trace" >/dev/null
mode normal failNext=2
retried="$(trace_id sku-trace)"          # 503, 503, then 200: three attempts under one trace
mode fail503
stale="$(trace_id sku-trace)"            # provider down: three attempts, then the cached quote, stale=true
mode hang
seq 1 40 | xargs -P 40 -I{} curl -sS -o /dev/null "$PRICE/price/sku-fill-{}" &   # fill every slot with a hanging call
sleep 1
refused="$(trace_id sku-trace)"          # all slots taken: refused at once, no provider call, stale answer
wait
mode normal
sleep 6                                    # let the exporter flush
summarize "$retried" trace-retried
summarize "$stale" trace-stale-after-failure
summarize "$refused" trace-refused-no-provider-call
