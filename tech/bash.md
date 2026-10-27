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

A **terminal** is a window where you type commands as text. The **shell** is the program inside it that reads each line, finds the program you name (it looks through the folders listed in the environment variable `PATH`), and runs it in the **working directory**, the folder you are currently in. A **script** is a file of such commands. Bash is the shell this repository's scripts are written for; macOS also ships zsh, which is similar for the simple commands you type.

Words first. Each is used in the walkthrough below.

- A **process** is a running program. When a shell runs `curl`, the operating system starts a new process for `curl`; when it ends, it returns an **exit code**, a number: `0` means success, anything else means failure.
- **Standard output** (stdout) is where a program writes its normal results; **standard error** (stderr) is where it writes problems. Both appear on your screen unless redirected. **Standard input** (stdin) is where it reads input from.
- A **redirection** sends a stream elsewhere: `> file` writes stdout to a file, `2>&1` sends stderr to the same place as stdout, `< file` reads stdin from a file, `>/dev/null` throws output away (`/dev/null` is a special file that discards everything).
- A **pipe** `a | b` connects a's stdout to b's stdin, so the two run together and b processes what a prints.
- An **environment variable** is a named value that every process receives from the one that started it (`HOME`, `PATH`, `APP_PORT`). `NAME=value command` sets one for that command only.
- A **variable** of the script itself is set with `NAME=value` (no spaces around `=`) and read with `$NAME` or `${NAME}`. `$(command)` runs the command and gives its stdout. `${1:-default}` is the first argument, or `default` if there is none; `${2:+&$2}` is `&` plus the second argument if there is a second argument, otherwise nothing.
- **Quoting:** inside double quotes `"..."` the shell still expands `$NAME` and `$(...)` but keeps spaces together as one word; inside single quotes `'...'` nothing is expanded.
- `a && b` runs b only if a succeeded; `a || b` only if a failed; `a; b` runs b after a whatever happened. `( ... )` runs the commands in a **subshell**, a child shell whose `cd` and variables do not leak back. `{ ...; }` groups commands without a child shell.
- A **function** is a named block: `name() { ...; }`; `$1` is its first argument.
- A **background job**: a trailing `&` starts the command and lets the script go on; `$!` is the id of the last one (a **process id**, PID); `wait` blocks until background jobs end; `kill PID` asks a process to stop (it sends the **signal** SIGTERM, a polite request).
- `set -euo pipefail` at the top changes how failures behave: `-e` stops the script at the first failing command; `-u` makes an unset variable an error; `-o pipefail` makes a pipeline fail if any stage fails, not only the last.
- `trap 'command' EXIT` runs a command when the script ends, however it ends.
- A **regular expression** (regex) is a pattern for text, used by `grep -E`, `sed` and `awk`: `^` start of a line, `$` end, `.` any character, `*` repeat, `|` or, `[a-z]` one character from a set.
- A **here-string** `<<<"text"` feeds the text to a command's stdin.
- `/dev/tcp/host/port` is a special name that **bash** (not other shells) treats as a network connection, and a **file descriptor** is a small number a process uses to refer to an open file or connection (0 stdin, 1 stdout, 2 stderr; bash lets you open more, such as 3).

The shebang `#!/usr/bin/env bash` on the first line tells the system to run the file with bash. A script becomes a command when the file is marked executable (`chmod +x`).

## Walkthrough of this repository's use

Each script is printed whole with line numbers, and every line is explained after it.

### `scripts/scenario.sh` (`make run`)

```bash
 1  #!/usr/bin/env bash
 2  # The lesson's steps 1, 3, 4 and 5 against the running stack, with the provider's own counters as the witness.
 3  # Usage: scripts/scenario.sh [price-url] [provider-url]
 4  set -euo pipefail
 5
 6  PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
 7  PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
 8  LOADGEN="java -jar loadgen/target/loadgen-0.1.0.jar"
 9
10  heading() { printf '\n== %s\n' "$1"; }
11  mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
12  calls() { curl -fsS "$PROVIDER/admin/stats" | jq -r .callsReceived; }
13  maxflight() { curl -fsS "$PROVIDER/admin/stats" | jq -r .maxInFlight; }
14
15  heading "Step 1: provider in hang mode; a never-priced SKU fails in about the 2 s read timeout with a 504 problem"
16  mode hang
17  curl -sS -o /tmp/price-hang.json -w 'HTTP %{http_code} in %{time_total}s\n' "$PRICE/price/sku-hang-demo"
18  jq -c . /tmp/price-hang.json
19
20  heading "Step 2: baseline against the healthy provider, 100 requests a second for 20 s (p50, p99, error rate)"
21  mode normal
22  $LOADGEN --target "$PRICE/price/{sku}" --skus 10 --rps 100 --seconds 20 --side "$PRICE/skus/sku-1" --side-rps 10 | jq -c .
23
24  heading "Step 3: calls the provider receives for ONE price request"
25  mode fail503
26  curl -sS -o /dev/null -w 'fail503: HTTP %{http_code}   ' "$PRICE/price/sku-retry-demo"; echo "provider calls: $(calls)   (1 original + 2 retries)"
27  mode hang
28  curl -sS -o /dev/null -w 'hang:    HTTP %{http_code}   ' "$PRICE/price/sku-timeout-demo"; echo "provider calls: $(calls)   (a timeout is not retried)"
29  mode slow delayMs=5000
30  curl -sS -o /dev/null -w 'slow:    HTTP %{http_code}   ' "$PRICE/price/sku-slow-demo"; echo "provider calls: $(calls)   (a timeout is not retried)"
31
32  heading "Step 4: 60 concurrent requests for 60 different SKUs against a slow provider; the provider's own maximum in flight"
33  mode slow delayMs=3000
34  seq 1 60 | xargs -P 60 -I{} curl -sS -o /dev/null -w '%{http_code}\n' "$PRICE/price/sku-burst-{}" | sort | uniq -c | sed 's/^/  HTTP /'
35  echo "provider max in flight: $(maxflight)   (the limit is PRICE_PROVIDER_MAX_CONCURRENT_CALLS, default 10)"
36  curl -sS -o /dev/null -w 'the catalog endpoint during a slow provider: HTTP %{http_code} in %{time_total}s\n' "$PRICE/skus/sku-1"
37
38  heading "Step 5: stale with a label"
39  mode normal
40  curl -fsS "$PRICE/price/sku-1" | jq -c .
41  mode fail503
42  echo "provider down, sku-1 priced a moment ago:"; curl -fsS "$PRICE/price/sku-1" | jq -c .
43  echo "provider down, never priced:"; curl -sS -w '\nHTTP %{http_code}\n' "$PRICE/price/sku-never-priced"
44  mode normal
```

- Lines 2 and 3: comments. Line 4: `set -euo pipefail` (explained above).
- Lines 6 and 7: `PRICE` and `PROVIDER` are the two base addresses: the first and second arguments, or `http://localhost:` plus `$PRICE_PORT` (default 18104) or `$PROVIDER_PORT` (default 18103).
- Line 8: the command that runs the load generator jar (`java -jar` starts a Java program packaged in a jar).
- Line 10: the `heading` function.
- Line 11: `mode()` switches the provider. `curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}"` builds the address with the first argument as the mode; `${2:+&$2}` appends `&` and the second argument **only if** a second argument exists. `>/dev/null` hides the answer. After `;` a second `curl -X POST .../admin/reset` zeroes the provider\'s counters.
- Lines 12 and 13: `calls()` and `maxflight()` read the provider\'s own counters from `/admin/stats` and use `jq -r` to print one number.
- Lines 15 to 18: step 1. `mode hang` makes the provider silent. `curl -sS -o /tmp/price-hang.json -w 'HTTP %{http_code} in %{time_total}s\n' ...` saves the body to a file (`-o`) and prints the status and the elapsed seconds (`-w`): about 2 seconds, the read timeout. Line 18 prints the saved body with `jq -c .`.
- Lines 20 to 22: step 2. `mode normal`, then the load generator runs for 20 seconds. `$LOADGEN` is left unquoted on purpose so the shell splits it into the words `java`, `-jar` and the path. The options: `--target` the URL pattern (`{sku}` is replaced per request), `--skus 10` ten different SKUs, `--rps 100` a hundred requests a second, `--seconds 20`, `--side` and `--side-rps` a second target at ten a second. Its JSON lines go through `jq -c .`.
- Lines 24 to 30: step 3. For each mode one request is sent and the provider\'s call counter is read. `curl -sS -o /dev/null -w 'fail503: HTTP %{http_code}   '` prints the status without a newline; then `echo "provider calls: $(calls) ..."` appends the count. `mode slow delayMs=5000` passes a second argument that `mode` turns into `&delayMs=5000`.
- Lines 32 to 36: step 4. `seq 1 60 | xargs -P 60 -I{} curl ...` prints the numbers 1 to 60 and `xargs` runs one `curl` for each: `-P 60` runs up to 60 at the same time, and `-I{}` replaces `{}` in the command by the number. Each prints only the status code (`-w '%{http_code}\n'`). `sort | uniq -c` sorts the codes and counts equal neighbors, and `sed 's/^/  HTTP /'` puts a label in front of each line. Line 35 prints the provider\'s own maximum in flight. Line 36 times one request to the catalog endpoint, which never calls the provider.
- Lines 38 to 44: step 5. A normal answer, then the provider is switched to `fail503`: a SKU priced a moment ago answers `stale: true`; a never-priced SKU answers the 503 problem (`-w '\nHTTP %{http_code}\n'` adds the status after the body). The last line puts the provider back to normal.

### `scripts/benchmark.sh` (`make benchmark`)

```bash
 1  #!/usr/bin/env bash
 2  # The lesson's step 6 table: p50, p99 and error rate for every provider mode, before (price.protection=none:
 3  # no timeouts, retries, limit or cache) and after (price.protection=full), at the same load. The provider's own
 4  # call count is the independent witness. Writes docs/benchmark.md.
 5  # Usage: scripts/benchmark.sh [price-url] [provider-url]   (env: RPS=100 SECONDS_PER_RUN=20)
 6  set -euo pipefail
 7
 8  PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
 9  PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
10  RPS="${RPS:-100}"
11  SECONDS_PER_RUN="${SECONDS_PER_RUN:-20}"
12  LOADGEN="java -jar loadgen/target/loadgen-0.1.0.jar"
13  OUT=docs/benchmark.md
14
15  mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
16  wait_ready() { until curl -fsS "$PRICE/actuator/health/readiness" 2>/dev/null | jq -e '.status == "UP"' >/dev/null; do sleep 2; done; }
17
18  {
19    echo "# Benchmark: before and after"
20    echo
21    echo "$RPS requests a second for ${SECONDS_PER_RUN} s per run against 10 SKUs, plus 10 requests a second to the catalog endpoint (\`/skus/sku-1\`), which never calls the provider. Slow mode answers after 5 s, hang mode after 10 s. 'Before' is \`price.protection=none\`; 'after' is the service this project builds. Run on $(date -u +%Y-%m-%d) on $(uname -sm); numbers depend on the machine."
22    echo
23    echo "| protection | provider mode | p50 (ms) | p99 (ms) | error rate | stale answers | provider calls | catalog p99 (ms) |"
24    echo "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"
25  } > "$OUT"
26
27  for protection in none full; do
28    PRICE_PROTECTION="$protection" docker compose up -d --force-recreate --no-deps price-service >/dev/null
29    wait_ready
30    for provider_mode in normal "slow delayMs=5000" fail503 hang; do
31      mode normal
32      for sku in $(seq 0 9); do curl -fsS "$PRICE/price/sku-$sku" >/dev/null; done   # warm the cache (full only)
33      mode $provider_mode
34      report="$($LOADGEN --target "$PRICE/price/{sku}" --skus 10 --rps "$RPS" --seconds "$SECONDS_PER_RUN" \
35        --side "$PRICE/skus/sku-1" --side-rps 10)"
36      calls="$(curl -fsS "$PROVIDER/admin/stats" | jq .callsReceived)"
37      price="$(echo "$report" | jq -s '.[] | select(.name == "price")')"
38      side="$(echo "$report" | jq -s '.[] | select(.name == "side")')"
39      printf '| %s | %s | %s | %s | %s%% | %s | %s | %s |\n' "$protection" "${provider_mode%% *}" \
40        "$(jq -r .p50Ms <<<"$price")" "$(jq -r .p99Ms <<<"$price")" \
41        "$(jq -r '.errorRate * 100 | round' <<<"$price")" "$(jq -r .stale <<<"$price")" "$calls" "$(jq -r .p99Ms <<<"$side")" | tee -a "$OUT"
42    done
43  done
44  mode normal
45  PRICE_PROTECTION=full docker compose up -d --force-recreate --no-deps price-service >/dev/null
46  echo; echo "written to $OUT"
```

- Lines 2 to 5: comments (what it measures and its two optional settings). Line 6: strict mode.
- Lines 8 and 9: the two addresses, as in the scenario. Lines 10 and 11: `RPS` and `SECONDS_PER_RUN` can be set from the environment (`${RPS:-100}`).
- Lines 12 and 13: the load generator command and the output file.
- Line 15: the same `mode()` function. Line 16: `wait_ready()` repeats `until` the readiness answer says UP (`jq -e` exits non-zero when the expression is false), sleeping 2 seconds between tries; `2>/dev/null` hides errors while the service is starting.
- Lines 18 to 25: a group `{ ...; } > "$OUT"` whose output all goes into the file, overwriting it. The `echo` lines write the title, a description (with `$(date -u +%Y-%m-%d)` the date in UTC and `$(uname -sm)` the operating system and machine type) and the table header.
- Line 27: loop over the two protection modes. Line 28: restarts the price service with that mode: `PRICE_PROTECTION="$protection" docker compose up -d --force-recreate --no-deps price-service` sets the environment variable only for this command, which compose substitutes into the service\'s environment; `-d` runs in the background, `--force-recreate` replaces the container, `--no-deps` leaves the other services alone. Line 29: waits until it is ready.
- Line 30: loop over the four provider modes; a value with a space is in quotes so it stays one word.
- Lines 31 and 32: provider normal, then price each of the ten SKUs once so the cache holds a quote (only the protected version has a cache). `$(seq 0 9)` is 0 to 9.
- Line 33: switches to the mode being measured (`$provider_mode` unquoted on purpose so `slow delayMs=5000` splits into two arguments).
- Lines 34 and 35: runs the load generator and stores its output; the trailing `\` continues the line.
- Line 36: reads the provider\'s call count. Lines 37 and 38: `jq -s '.[] | select(.name == "price")'` picks, out of the generator\'s one-JSON-per-line output, the object whose name is `price`, and the one named `side`.
- Lines 39 to 41: `printf` writes one table row with eight values. `${provider_mode%% *}` strips everything from the first space (so `slow delayMs=5000` prints as `slow`). `jq -r .p50Ms <<<"$price"` reads one field from a variable via a here-string. `.errorRate * 100 | round` turns a fraction into a whole percent. `| tee -a "$OUT"` shows the row on screen and appends it to the file.
- Lines 42 and 43 end the loops. Line 44 resets the provider; line 45 restarts the price service in the protected mode; line 46 prints where the table was written.

### `scripts/capture-traces.sh` (`make traces`)

```bash
 1  #!/usr/bin/env bash
 2  # The lesson's step 6 trace evidence, as text instead of screenshots: one trace of a request that retried and
 3  # succeeded, one of a request served stale after the provider failed, one of a request refused by the concurrency
 4  # limit (no provider call at all). Each trace is fetched from Tempo by the id the service returned in X-Trace-Id.
 5  # Usage: scripts/capture-traces.sh [price-url] [provider-url] [tempo-url]
 6  set -euo pipefail
 7
 8  PRICE="${1:-http://localhost:${PRICE_PORT:-18104}}"
 9  PROVIDER="${2:-http://localhost:${PROVIDER_PORT:-18103}}"
10  TEMPO="${3:-http://localhost:${TEMPO_PORT:-13200}}"
11  OUT=docs/evidence
12  mkdir -p "$OUT"
13
14  mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
15  trace_id() { curl -sS -D - -o /dev/null "$PRICE/price/$1" | tr -d '\r' | awk -F': ' 'tolower($1) == "x-trace-id" { print $2 }'; }
16
17  summarize() { # trace id -> one line per span: name, duration in ms, status
18    local id="$1" attempts=0
19    until [ "$attempts" -ge 15 ]; do
20      if curl -fsS "$TEMPO/api/traces/$id" -o "$OUT/$2.json" 2>/dev/null; then break; fi
21      attempts=$((attempts + 1)); sleep 2
22    done
23    jq -r '[.. | objects | select(has("spans")) | .spans[]?] | sort_by(.startTimeUnixNano | tonumber)
24           | .[] | "\(.name)\t\(((.endTimeUnixNano | tonumber) - (.startTimeUnixNano | tonumber)) / 1000000 | floor) ms"' \
25      "$OUT/$2.json" > "$OUT/$2.txt"
26    echo "== $2 (trace $id)"; cat "$OUT/$2.txt"
27  }
28
29  mode normal; curl -fsS "$PRICE/price/sku-trace" >/dev/null
30  mode normal failNext=2
31  retried="$(trace_id sku-trace)"          # 503, 503, then 200: three attempts under one trace
32  mode fail503
33  stale="$(trace_id sku-trace)"            # provider down: three attempts, then the cached quote, stale=true
34  mode hang
35  seq 1 40 | xargs -P 40 -I{} curl -sS -o /dev/null "$PRICE/price/sku-fill-{}" &   # fill every slot with a hanging call
36  sleep 1
37  refused="$(trace_id sku-trace)"          # all slots taken: refused at once, no provider call, stale answer
38  wait
39  mode normal
40  sleep 6                                    # let the exporter flush
41  summarize "$retried" trace-retried
42  summarize "$stale" trace-stale-after-failure
43  summarize "$refused" trace-refused-no-provider-call
```

- Lines 2 to 5: comments. Line 6: strict mode.
- Lines 8 to 10: three addresses (price service, provider, Tempo\'s query API). Lines 11 and 12: the output folder; `mkdir -p` creates it and does not complain if it exists.
- Line 14: the `mode()` function again.
- Line 15: `trace_id()` calls the price API with `curl -sS -D - -o /dev/null`: `-D -` dumps the response headers to stdout and `-o /dev/null` discards the body. `tr -d '\r'` removes carriage returns. `awk -F': ' 'tolower($1) == "x-trace-id" { print $2 }'` splits each line at `: ` (`-F`), and when the header name, in lower case, is `x-trace-id` prints its value: the trace id.
- Lines 17 to 27: `summarize()`. Line 18: `local id="$1" attempts=0` declares variables local to the function. Lines 19 to 22: an `until` loop that tries up to 15 times to fetch the trace from Tempo and save it (`-o file`); `break` leaves the loop on success; otherwise it adds one to `attempts` with `$((attempts + 1))` (arithmetic) and sleeps 2 seconds, because Tempo needs a few seconds after the request. Lines 23 to 25: `jq` lists the spans: `.. | objects | select(has("spans")) | .spans[]?` searches the whole answer for objects that have a `spans` list and walks through them (`[]?` ignores a missing list); `sort_by(.startTimeUnixNano | tonumber)` orders by start time; the string `"\(.name)\t\(...)ms"` prints the span name, a tab, and the duration in milliseconds (end minus start, divided by a million because the times are in nanoseconds, rounded down with `floor`). The output goes to a `.txt` file. Line 26 prints the title and the file.
- Line 29: provider normal, price `sku-trace` once so it is remembered. Line 30: `failNext=2` makes the next two provider calls fail.
- Line 31: `retried=...` stores the trace id of a request that fails twice and then succeeds (three attempts, one trace). Line 32: the provider is now down. Line 33: a request for the same SKU: three failed attempts, then the remembered quote, labeled stale.
- Lines 34 to 37: the provider is made to hang. Line 35 starts 40 requests in the background (`&`) so every concurrency slot is taken by a hanging call; line 36 waits a second; line 37 sends one more request, which is refused at once without calling the provider and answered from the cache.
- Line 38: `wait` blocks until the 40 background requests end. Line 39: provider back to normal. Line 40: waits 6 seconds so the service has exported its spans. Lines 41 to 43: fetch and summarize the three traces.

### `scripts/audit.sh` (`make audit`)

```bash
1  #!/usr/bin/env bash
2  # Dependency audit (OSV-Scanner 2.7.0, pinned by digest): OSV-Scanner reads pom.xml (with the Spring Boot parent's managed versions) and reports
3  # known vulnerabilities. Fails on any finding that is not listed, with a reason, in osv-scanner.toml.
4  set -euo pipefail
5
6  cd "$(dirname "$0")/.."
7  IMAGE="${OSV_SCANNER_IMAGE:-ghcr.io/google/osv-scanner@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0}"
8  docker run --rm -v "$PWD:/src:ro" -w /src "$IMAGE" scan source --lockfile=pom.xml --config=osv-scanner.toml
```

- Line 4: strict mode. Line 6: moves to the project root.
- Line 7: `IMAGE` from the environment or the pinned default (OSV-Scanner 2.7.0, fixed by digest).
- Line 8: runs the scanner in a throwaway container with the project mounted read-only at `/src`, reading the dependency list from `pom.xml`.

**The container health checks** are one-line bash commands inside `docker-compose.yml` because the Java images have no `curl`:

```yaml
test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8081 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
```

Read it in pieces: `["CMD", "bash", "-c", "..."]` runs bash with the string after `-c` as the program. `exec 3<>/dev/tcp/localhost/PORT` opens a network connection to the port on this machine and attaches it, for reading and writing (`<>`), to file descriptor 3. `printf 'GET /actuator/health/readiness HTTP/1.0\r\n\r\n' >&3` writes an HTTP request into it (`>&3` redirects stdout to descriptor 3). `grep -q '"status":"UP"' <&3` reads the answer from descriptor 3 (`<&3`) and succeeds only if the text appears. The `&&` between the three parts means each runs only if the one before succeeded, so the check passes only when the server answers UP.

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
