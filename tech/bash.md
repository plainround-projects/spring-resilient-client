# Bash (the scripts)

## What it is

Bash is a **shell**: a program that reads commands you type (or a file of commands, a **script**) and runs them. A script starts with a **shebang** line, `#!/usr/bin/env bash`, which tells the system to run the file with bash. Commands are separated by new lines. A command that succeeds ends with **exit code** 0; a failing one ends with a non-zero code.

## Why this repository uses it

A reader should be able to see each "Done when" line of the lesson with one command. Scripts do that with tools every Mac and Linux machine has (`curl`, `jq`, `bash`). Bash is also the right size: each script is a short list of commands, not a program.

## Where it appears

| Path | What it does |
| --- | --- |
| `scripts/scenario.sh` | steps 1 to 5 of the lesson against the running stack (`make run`) |
| `scripts/benchmark.sh` | before/after table for each provider mode (`make benchmark`) |
| `scripts/capture-traces.sh` | fetches three traces from Tempo (`make traces`) |
| `scripts/audit.sh` | runs the dependency scanner in a container (`make audit`) |
| `docker-compose.yml` health checks | a one-line bash command inside each container |

## How it works underneath

1. `bash script.sh` (or `./script.sh` if the file is executable) reads the file line by line and runs each command as a separate process. A **pipe** `a | b` connects a's output to b's input. `a && b` runs b only if a succeeded; `a || b` only if a failed.
2. A **variable** is set with `NAME=value` (no spaces) and read with `$NAME`. `$(command)` runs the command and substitutes its output. Double quotes `"$NAME"` keep a value with spaces as one word; single quotes `'...'` keep everything literal.
3. `${1:-default}` means "the first argument, or `default` if there is none". `$0` is the script's own path.
4. `set -euo pipefail` at the top changes how failures behave: `-e` stops the script at the first failing command; `-u` makes an unset variable an error; `-o pipefail` makes a pipeline fail if any stage fails, not only the last.
5. `trap 'command' EXIT` runs a command when the script ends, however it ends. It is used here to delete temporary folders.
6. `curl` talks HTTP and `jq` reads JSON (see `curl.md` and `jq.md`).

## Walkthrough of this repository's use

### `scripts/scenario.sh`

```bash
set -euo pipefail

PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
LOADGEN="java -jar loadgen/target/loadgen-0.1.0.jar"

heading() { printf '\n== %s\n' "$1"; }
mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
calls() { curl -fsS "$PROVIDER/admin/stats" | jq -r .callsReceived; }
maxflight() { curl -fsS "$PROVIDER/admin/stats" | jq -r .maxInFlight; }
```

- `PRICE="${1:-...}"` and `PROVIDER="${2:-...}"` take the two base URLs from the arguments with defaults from `$PRICE_PORT` and `$PROVIDER_PORT`.
- `LOADGEN="java -jar loadgen/target/loadgen-0.1.0.jar"` is the load generator command.
- `mode()` switches the provider: `curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}"` builds the URL with the first argument as the mode and, **only if** a second argument exists (`${2:+&$2}`), appends `&` and it; then `curl -X POST .../admin/reset` zeroes the provider's counters. `>/dev/null` hides the output.
- `calls()` and `maxflight()` read the provider's own counters: `curl ... /admin/stats | jq -r .callsReceived`.

```bash
heading "Step 4: 60 concurrent requests for 60 different SKUs against a slow provider; the provider's own maximum in flight"
mode slow delayMs=3000
seq 1 60 | xargs -P 60 -I{} curl -sS -o /dev/null -w '%{http_code}\n' "$PRICE/price/sku-burst-{}" | sort | uniq -c | sed 's/^/  HTTP /'
echo "provider max in flight: $(maxflight)   (the limit is PRICE_PROVIDER_MAX_CONCURRENT_CALLS, default 10)"
curl -sS -o /dev/null -w 'the catalog endpoint during a slow provider: HTTP %{http_code} in %{time_total}s\n' "$PRICE/skus/sku-1"
```

`seq 1 60 | xargs -P 60 -I{} curl ... "$PRICE/price/sku-burst-{}"` prints the numbers 1 to 60 and runs 60 `curl` commands **in parallel** (`-P 60`), replacing `{}` with each number. `sort | uniq -c` counts how many answers had each status code, and `sed 's/^/  HTTP /'` indents them. Then the provider's `maxInFlight` is printed, and one request to the catalog shows it still answers quickly (`%{time_total}` prints the time).

### `scripts/benchmark.sh`

```bash
set -euo pipefail

PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
RPS="${RPS:-100}"
SECONDS_PER_RUN="${SECONDS_PER_RUN:-20}"
LOADGEN="java -jar loadgen/target/loadgen-0.1.0.jar"
OUT=docs/benchmark.md

mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
wait_ready() { until curl -fsS "$PRICE/actuator/health/readiness" 2>/dev/null | jq -e '.status == "UP"' >/dev/null; do sleep 2; done; }
```

`RPS="${RPS:-100}"` lets you override the rate from the environment. `wait_ready()` loops `until curl ... | jq -e '.status == "UP"'` succeeds (`jq -e` exits non-zero when the expression is false), sleeping 2 seconds between tries.

```bash
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
```

Two nested `for` loops: protection `none` then `full`, and the four provider modes. For each: restart the price service with `PRICE_PROTECTION=... docker compose up -d --force-recreate --no-deps price-service`, warm the cache by pricing the ten SKUs (`for sku in $(seq 0 9)`), switch the mode, run the load generator, read the provider's call count, pull numbers out of the two JSON reports with `jq`, and append a table row with `printf` and `tee -a`. `${provider_mode%% *}` strips everything after the first space (so `slow delayMs=5000` prints as `slow`).

### `scripts/capture-traces.sh`

```bash
trace_id() { curl -sS -D - -o /dev/null "$PRICE/price/$1" | tr -d '\r' | awk -F': ' 'tolower($1) == "x-trace-id" { print $2 }'; }

summarize() { # trace id -> one line per span: name, duration in ms, status
```

`trace_id()` makes a request with `curl -D -` (dump headers to standard output) and `awk -F': '` splits each line at `: ` and prints the value of the header named `x-trace-id` (compared in lower case).

```bash
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
```

`summarize()` fetches the trace from Tempo, retrying up to 15 times 2 seconds apart (Tempo needs a few seconds after the request), saves the JSON, and uses `jq` to list every span with its name and duration in milliseconds, sorted by start time.

```bash
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
```

The three scenarios: 503 twice then success (`failNext=2`), provider down, and every concurrency slot taken. The third starts 40 hanging requests in the background with a trailing `&` and then asks for one more; `wait` blocks until the background jobs end.

### `scripts/audit.sh`

```bash
IMAGE="${OSV_SCANNER_IMAGE:-ghcr.io/google/osv-scanner@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0}"
docker run --rm -v "$PWD:/src:ro" -w /src "$IMAGE" scan source --lockfile=pom.xml --config=osv-scanner.toml
```

Runs the dependency scanner in a throwaway container with the project mounted read-only; the image is pinned by digest.

**The container health checks** use bash because the Java images have no `curl`:

```yaml
test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8081 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
```

`exec 3<>/dev/tcp/localhost/PORT` opens a TCP connection and attaches it to file descriptor 3 (bash treats `/dev/tcp/host/port` as a special file); `printf 'GET ... HTTP/1.0\r\n\r\n' >&3` writes an HTTP request into it; `grep -q ... <&3` reads the answer from it and succeeds if it contains the text. No outside tool is needed.

## Try it yourself

1. After `make up`: `scripts/scenario.sh`.
2. `bash -n scripts/benchmark.sh` checks the syntax.
3. `bash -x scripts/capture-traces.sh 2>&1 | head -40` shows the first commands with variables expanded.

5. `shellcheck scripts/*.sh` (if installed) finds common mistakes.

## Mistakes people make

- Unquoted variables: `rm -rf $DIR` with an empty `DIR` is a disaster; `"$DIR"` is safe.
- Forgetting `set -euo pipefail`: a failed step is ignored and the script reports success.
- Spaces around `=` in an assignment: `NAME = value` is a command named `NAME`.
- Parsing JSON with `grep`/`sed`: use `jq`.
- Assuming the script runs from the project root: scripts here `cd "$(dirname "$0")/.."` first when they need it.

## Check yourself

1. What does `set -e` do? *Stops the script at the first failing command.*
2. What is `$(...)`? *Runs a command and substitutes its output.*
3. What does `${1:-x}` mean? *The first argument, or `x` if absent.*
4. What does `trap '...' EXIT` do? *Runs the command when the script ends.*
5. What does a trailing `&` do? *Runs the command in the background.*

## Further reading

- GNU Bash Reference Manual: https://www.gnu.org/software/bash/manual/bash.html
- ShellCheck wiki (common mistakes): https://www.shellcheck.net/wiki/
