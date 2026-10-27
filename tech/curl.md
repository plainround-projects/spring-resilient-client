# curl

## What it is

`curl` is a command-line program that makes HTTP requests and prints the answer. It is the simplest way to call a web service by hand. It is installed on macOS and most Linux systems.

## Why this repository uses it

Every "Done when" line that is about an HTTP answer is shown with a `curl` command, so a reader sees exactly the request and the response. The scripts and the container health checks use the same idea.

## Where it appears

| Path | Role |
| --- | --- |
| `scripts/scenario.sh` | the lesson's requests |
| `Makefile` (`make health`) | calls the readiness endpoint |
| `scripts/benchmark.sh`, `scripts/capture-traces.sh` | switch the provider mode, read its counters, fetch traces |
| `README.md` | example commands |

## How it works underneath

1. `curl URL` opens a TCP connection to the host and port in the URL, sends `GET /path HTTP/1.1` plus headers, reads the response (status line, headers, body) and prints the body.
2. `-X POST` changes the method. `-d 'text'` sets the request body (and implies POST). `-H 'Name: value'` adds a header; `Content-Type: application/json` tells the server the body is JSON.
3. The exit code of `curl` is 0 on a completed HTTP exchange of any status, unless `-f` is used, which makes HTTP errors (4xx, 5xx) a failure.

## Walkthrough of this repository's use

The flags that appear in this repository:

| Flag | Meaning |
| --- | --- |
| `-s` | silent: no progress meter |
| `-S` | with `-s`, still print errors |
| `-f` | fail (non-zero exit) on HTTP errors; used where an error would be a bug |
| `-i` | include the response headers in the output |
| `-I` or `-D -` | show only headers / dump headers to standard output |
| `-o /dev/null` | throw the body away |
| `-w '%{http_code}\n'` | after the transfer, print a value: here the status code (also `%{time_total}`) |
| `-X METHOD` | choose the method |
| `-d 'json'` | send a body |
| `-H 'Content-Type: application/json'` | add a header |

```bash
mode() { curl -fsS -X PUT "$PROVIDER/admin/mode?mode=$1${2:+&$2}" >/dev/null; curl -fsS -X POST "$PROVIDER/admin/reset" >/dev/null; }
calls() { curl -fsS "$PROVIDER/admin/stats" | jq -r .callsReceived; }
```

The provider is controlled with `curl -X PUT 'http://localhost:18103/admin/mode?mode=hang'` and `curl -X POST .../admin/reset`; its counters are read with `curl .../admin/stats`.

```bash
curl -sS -o /tmp/price-hang.json -w 'HTTP %{http_code} in %{time_total}s\n' "$PRICE/price/sku-hang-demo"
jq -c . /tmp/price-hang.json
```

`-w 'HTTP %{http_code} in %{time_total}s\n'` prints the status and the time the request took: with the provider hanging this is about 2 seconds, the read timeout.

## Try it yourself

1. `curl -s localhost:18104/actuator/health` prints the health JSON.
2. `curl -si localhost:18104/actuator/health | head -8` shows the status line and headers.
3. `curl -s -o /dev/null -w '%{http_code} %{time_total}s\n' localhost:18104/actuator/health` prints just `200 0.0xxs`.
4. `curl -v` shows the whole exchange, including the request curl sent.

## Mistakes people make

- Forgetting `-H 'Content-Type: application/json'` with a JSON body: the server answers 415.
- Mixing up the quotes in a JSON body so the shell eats the double quotes.
- Using `curl` in a script without `-f` and treating a 500 as success.
- Using `localhost` from inside a container to reach another container.

## Check yourself

1. What does `-X POST -d '...'` do? *Sends a POST with that body.*
2. What does `-f` change? *HTTP error statuses make curl exit non-zero.*
3. How do you print only the status code? *`-s -o /dev/null -w '%{http_code}'`.*
4. Why single quotes around JSON? *The shell then keeps the double quotes inside.*
5. What does `-i` add? *The response headers.*

## Further reading

- curl manual: https://curl.se/docs/manpage.html
- Everything curl: https://everything.curl.dev/
