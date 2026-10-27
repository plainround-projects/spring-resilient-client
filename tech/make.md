# Make and the Makefile

## What it is

`make` is a command-line tool that runs named tasks defined in a file called `Makefile`. Each task is a **target**. A target has a **recipe**: the shell commands to run, one per line, each starting with a **tab** character. A target can list **prerequisites** (other targets that must run first).

## Why this repository uses it

The project rule is that a reader needs only a few commands: `make up`, `make run`, `make test`, `make lint`, `make down`. The Makefile hides long commands behind those names and is the same in every repository.

## Where it appears

| Path | Role |
| --- | --- |
| `Makefile` | all the targets |
| `.github/workflows/ci.yml` | CI runs `make lint`, `make test`, `make audit` and the image build |
| `.env` | variables that `make` reads (see below) |

## How it works underneath

1. `make up` finds the file `Makefile`, finds the target `up`, runs its prerequisites first, then runs each recipe line in a new shell.
2. A line starting with `@` is run without printing the command first. A line starting with `-` ignores a failing exit code. A trailing `\` continues the command on the next line.
3. `$(NAME)` expands a make variable. `NAME ?= value` sets a default that an environment variable or a command-line value can override. In a recipe, a shell variable needs a doubled dollar: `$$HOME`.
4. `.PHONY: up run test` says these names are tasks, not files. Without it, a file called `test` would make `make test` do nothing.
5. `-include .env` reads variable definitions from `.env` if the file exists. `export` makes every variable visible to the commands as environment variables.
6. If a command exits with a non-zero status, `make` stops and reports `Error N`.

## Walkthrough of this repository's use

First the whole top of the Makefile, before any target:

```make
# make up | run | benchmark | traces | test | lint | down   (make help lists all)
SHELL := /bin/bash
MVN := ./mvnw -B -ntp
PROVIDER_PORT ?= 18103
PRICE_PORT ?= 18104

-include .env
export

.PHONY: help env build up run benchmark traces serve health test test-unit lint format audit docker-build down clean

help: ## list the targets
```

- The first line is a comment (a line starting with `#`) listing the main targets.
- `SHELL := /bin/bash` makes `make` run recipe lines with bash instead of the default `sh`, which lacks some features the recipes use. `:=` assigns a value once, when the file is read.
- `MVN := ./mvnw -B -ntp` stores the Maven command with its flags in a variable: `./mvnw` is the wrapper (see `maven.md`), `-B` is batch mode (no colors or interactive prompts), `-ntp` means no transfer progress (do not print a line for each downloaded file).
- Lines such as `APP_PORT ?= 18101` set a variable only if it has no value yet, so `APP_PORT=18999 make up` overrides it. A **port** is the number a program listens on (see `properties-files.md`).
- `-include .env` reads variable definitions from the file `.env` if it exists (the leading `-` means "do not fail if it is missing"). `export` makes every variable visible to the commands that `make` starts, as environment variables.
- `.PHONY: ...` lists names that are tasks, not files. Without it, a file named `test` in the folder would make `make test` do nothing because that "target" would look up to date.
- `help:` begins the first target; its recipe is in the table below.

Every target in this Makefile, with its prerequisites, the exact commands, and what it does:

| Target | Runs first | Commands it runs | What it does |
| --- | --- | --- | --- |
| `help` | - | `@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) \| sed -E 's/:.*## /\t/' \| sort` | Prints every target that has a `## description` after its name. |
| `env` | - | `@[ -f .env ] \|\| { cp .env.example .env; echo "created .env from .env.example"; }` | Creates `.env` from `.env.example` if it does not exist yet. `.env` holds local settings such as ports. |
| `build` | - | `$(MVN) -DskipTests package` | Packages all modules into jars (no tests), because the load generator runs from its jar. |
| `up` | env | `docker compose up -d --build --wait` | Builds the images and starts the containers, then waits until they report healthy. |
| `run` | build | `scripts/scenario.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)` | Runs `scripts/scenario.sh`: the lesson's requests against the running service. |
| `benchmark` | build | `scripts/benchmark.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)` | Runs the before/after load measurements and writes `docs/benchmark.md`. |
| `traces` | - | `scripts/capture-traces.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT) http://localhost:$(TEMPO_PORT)` | Fetches three traces from Tempo and writes text summaries to `docs/evidence/`. |
| `serve` | env | `@echo "terminal 1: PORT=$(PROVIDER_PORT) java -jar provider/target/provider-0.1.0.jar"`<br>`@echo "terminal 2: PRICE_PROVIDER_BASE_URL=http://localhost:$(PROVIDER_PORT) SERVER_PORT=$(PRICE_PORT) $(MVN) -pl price-service spring-boot:run"` | Runs the application from source on your machine, in the foreground, without Docker for the application itself. |
| `health` | - | `curl -fsS http://localhost:$(PRICE_PORT)/actuator/health/readiness \| jq -e '.status == "UP"' >/dev/null && echo "price-service UP"`<br>`curl -fsS http://localhost:$(PROVIDER_PORT)/health >/dev/null && echo "provider UP"` | Asks the readiness endpoint and succeeds only if it says UP. |
| `test` | - | `$(MVN) verify` | Runs the whole Maven build: unit tests, integration tests and the coverage limits. |
| `test-unit` | - | `$(MVN) test` | Runs only the unit and slice tests, which need no Docker. |
| `lint` | - | `$(MVN) spotless:check checkstyle:check` | Checks formatting (Spotless) and style rules (Checkstyle); changes nothing. |
| `format` | - | `$(MVN) spotless:apply` | Rewrites the Java files into the project format. |
| `audit` | - | `scripts/audit.sh` | Runs the dependency vulnerability scan. |
| `docker-build` | - | `docker build -f provider/Dockerfile -t spring-resilient-client-provider:dev .`<br>`docker build -f price-service/Dockerfile -t spring-resilient-client-price:dev .` | Builds the container image(s) only, without starting anything. |
| `down` | - | `docker compose down -v --remove-orphans` | Stops and removes the containers, networks and data volumes. |
| `clean` | down | `$(MVN) clean` | Runs `down`, then deletes Maven's `target/` folders. |

How to read the table: "Runs first" lists the prerequisites, so `up` with `env` runs `env` and then `up`. "Commands it runs" shows each recipe line as written; `$(NAME)` is replaced by a variable's value, and `$$` is a literal `$` handed to the shell. A recipe line starting with `@` is not echoed before it runs, and a line starting with `-` ignores a failing exit code (the exit code is the number a program returns when it ends: 0 means success).

Four recipe details that are easy to misread:

- `[ -f .env ] || { cp .env.example .env; ... }` is shell: `[ -f .env ]` tests whether the file exists; `||` runs the right side only if the test failed; `{ ...; }` groups several commands.
- A backslash at the end of a line continues the command on the next line.
- `$$(openssl rand -hex 16)` (in the PostgreSQL project) asks `openssl` for 16 random bytes written as 32 hexadecimal characters; `$$( ... )` is shell command substitution, doubled so `make` leaves it alone.
- `docker compose exec -T postgres psql ...` runs a command inside the running database container; `-T` turns off the terminal so input can be piped in with `<`.

### The complete Makefile

The whole file, quoted, with a line-by-line reading. A line that starts with a tab belongs to the target above it and is a command to run; any other line is a variable, a setting or the start of a target.

### `Makefile`

The whole file, as it is in the repository:

```make
# make up | run | benchmark | traces | test | lint | down   (make help lists all)
SHELL := /bin/bash
MVN := ./mvnw -B -ntp
PROVIDER_PORT ?= 18103
PRICE_PORT ?= 18104

-include .env
export

.PHONY: help env build up run benchmark traces serve health test test-unit lint format audit docker-build down clean

help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

env:
	@[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }

build: ## package all modules (the load generator is run from its jar)
	$(MVN) -DskipTests package

up: env ## build both images, start the provider, the price service and the Grafana stack, wait until healthy
	docker compose up -d --build --wait

run: build ## the lesson's scenario step by step against the running stack (make up first)
	scripts/scenario.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)

benchmark: build ## the before/after table: every provider mode, 100 requests a second (make up first, about 6 minutes)
	scripts/benchmark.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)

traces: ## capture a retried trace and a stale trace from Tempo into docs/evidence (make up first)
	scripts/capture-traces.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT) http://localhost:$(TEMPO_PORT)

serve: env ## run the provider and the price service from source on the host (two terminals: see README)
	@echo "terminal 1: PORT=$(PROVIDER_PORT) java -jar provider/target/provider-0.1.0.jar"
	@echo "terminal 2: PRICE_PROVIDER_BASE_URL=http://localhost:$(PROVIDER_PORT) SERVER_PORT=$(PRICE_PORT) $(MVN) -pl price-service spring-boot:run"

health: ## exit 0 when the price service and the provider report healthy
	curl -fsS http://localhost:$(PRICE_PORT)/actuator/health/readiness | jq -e '.status == "UP"' >/dev/null && echo "price-service UP"
	curl -fsS http://localhost:$(PROVIDER_PORT)/health >/dev/null && echo "provider UP"

test: ## every module's unit, slice and integration tests, then the coverage gates
	$(MVN) verify

test-unit: ## unit and slice tests only
	$(MVN) test

lint: ## Spotless format check and Checkstyle on every module
	$(MVN) spotless:check checkstyle:check

format: ## apply the Spotless formatting
	$(MVN) spotless:apply

audit: ## known-vulnerability scan of the dependencies (OSV-Scanner in a container)
	scripts/audit.sh

docker-build: ## build both container images only
	docker build -f provider/Dockerfile -t spring-resilient-client-provider:dev .
	docker build -f price-service/Dockerfile -t spring-resilient-client-price:dev .

down: ## stop the containers
	docker compose down -v --remove-orphans

clean: down
	$(MVN) clean
```

Reading it, line by line:

- Comment (ignored by `make`): `make up | run | benchmark | traces | test | lint | down (make help lists all)`
- sets the variable `SHELL` to `/bin/bash`, once, immediately. Use it as `$(SHELL)`.
- sets the variable `MVN` to `./mvnw -B -ntp`, once, immediately. Use it as `$(MVN)`.
- sets the variable `PROVIDER_PORT` to `18103`, only if it is not already set (an environment variable or `.env` can override it). Use it as `$(PROVIDER_PORT)`.
- sets the variable `PRICE_PORT` to `18104`, only if it is not already set (an environment variable or `.env` can override it). Use it as `$(PRICE_PORT)`.
- `-include .env` reads the file `.env` as more `make` code (the leading `-` means: no error when it is missing). Here that loads the values in `.env`.
- `export` alone passes every `make` variable to the commands as environment variables.
- `.PHONY` lists the target names that are commands, not files, so `make` always runs them even if a file with that name exists: `help env build up run benchmark traces serve health test test-unit lint format audit docker-build down clean`.
- **target `help`**: Prints every target that has a `## description` after its name. The text after `##` (`list the targets`) is what `make help` prints.
  - runs `@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort` (the leading `@` stops `make` from printing the command before running it)
- **target `env`**: Creates `.env` from `.env.example` if it does not exist yet. `.env` holds local settings such as ports.
  - runs `@[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }` (the leading `@` stops `make` from printing the command before running it)
- **target `build`**: Packages all modules into jars (no tests), because the load generator runs from its jar. The text after `##` (`package all modules (the load generator is run from its jar)`) is what `make help` prints.
  - runs `$(MVN) -DskipTests package`
- **target `up`**, which first runs `env`: Builds the images and starts the containers, then waits until they report healthy. The text after `##` (`build both images, start the provider, the price service and the Grafana stack, wait until healthy`) is what `make help` prints.
  - runs `docker compose up -d --build --wait`
- **target `run`**, which first runs `build`: Runs `scripts/scenario.sh`: the lesson's requests against the running service. The text after `##` (`the lesson's scenario step by step against the running stack (make up first)`) is what `make help` prints.
  - runs `scripts/scenario.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)`
- **target `benchmark`**, which first runs `build`: Runs the before/after load measurements and writes `docs/benchmark.md`. The text after `##` (`the before/after table: every provider mode, 100 requests a second (make up first, about 6 minutes)`) is what `make help` prints.
  - runs `scripts/benchmark.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)`
- **target `traces`**: Fetches three traces from Tempo and writes text summaries to `docs/evidence/`. The text after `##` (`capture a retried trace and a stale trace from Tempo into docs/evidence (make up first)`) is what `make help` prints.
  - runs `scripts/capture-traces.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT) http://localhost:$(TEMPO_PORT)`
- **target `serve`**, which first runs `env`: Runs the application from source on your machine, in the foreground, without Docker for the application itself. The text after `##` (`run the provider and the price service from source on the host (two terminals: see README)`) is what `make help` prints.
  - runs `@echo "terminal 1: PORT=$(PROVIDER_PORT) java -jar provider/target/provider-0.1.0.jar"` (the leading `@` stops `make` from printing the command before running it)
  - runs `@echo "terminal 2: PRICE_PROVIDER_BASE_URL=http://localhost:$(PROVIDER_PORT) SERVER_PORT=$(PRICE_PORT) $(MVN) -pl price-service spring-boot:run"` (the leading `@` stops `make` from printing the command before running it)
- **target `health`**: Asks the readiness endpoint and succeeds only if it says UP. The text after `##` (`exit 0 when the price service and the provider report healthy`) is what `make help` prints.
  - runs `curl -fsS http://localhost:$(PRICE_PORT)/actuator/health/readiness | jq -e '.status == "UP"' >/dev/null && echo "price-service UP"`
  - runs `curl -fsS http://localhost:$(PROVIDER_PORT)/health >/dev/null && echo "provider UP"`
- **target `test`**: Runs the whole Maven build: unit tests, integration tests and the coverage limits. The text after `##` (`every module's unit, slice and integration tests, then the coverage gates`) is what `make help` prints.
  - runs `$(MVN) verify`
- **target `test-unit`**: Runs only the unit and slice tests, which need no Docker. The text after `##` (`unit and slice tests only`) is what `make help` prints.
  - runs `$(MVN) test`
- **target `lint`**: Checks formatting (Spotless) and style rules (Checkstyle); changes nothing. The text after `##` (`Spotless format check and Checkstyle on every module`) is what `make help` prints.
  - runs `$(MVN) spotless:check checkstyle:check`
- **target `format`**: Rewrites the Java files into the project format. The text after `##` (`apply the Spotless formatting`) is what `make help` prints.
  - runs `$(MVN) spotless:apply`
- **target `audit`**: Runs the dependency vulnerability scan. The text after `##` (`known-vulnerability scan of the dependencies (OSV-Scanner in a container)`) is what `make help` prints.
  - runs `scripts/audit.sh`
- **target `docker-build`**: Builds the container image(s) only, without starting anything. The text after `##` (`build both container images only`) is what `make help` prints.
  - runs `docker build -f provider/Dockerfile -t spring-resilient-client-provider:dev .`
  - runs `docker build -f price-service/Dockerfile -t spring-resilient-client-price:dev .`
- **target `down`**: Stops and removes the containers, networks and data volumes. The text after `##` (`stop the containers`) is what `make help` prints.
  - runs `docker compose down -v --remove-orphans`
- **target `clean`**, which first runs `down`: Runs `down`, then deletes Maven's `target/` folders.
  - runs `$(MVN) clean`


## Try it yourself

1. `make help` prints every target with its description.
2. `make -n test` prints the commands `make test` would run without running them.
3. `make lint` runs the style checks.
4. `APP_PORT=18999 make -n serve` shows the port variable passed through.

## Mistakes people make

- Spaces instead of a tab before a recipe line: `*** missing separator`.
- Forgetting `.PHONY`: a file with the target's name stops the task from running.
- Using `$VAR` instead of `$$VAR` for a shell variable inside a recipe.
- Expecting each recipe line to remember the previous one: each line is a new shell, so `cd` does not carry over (join lines with `&&` or `;`).

## Check yourself

1. What starts a recipe line? *A tab.*
2. What does `.PHONY` mean? *The target is a task, not a file.*
3. What does `@` before a command do? *Hides the command echo.*
4. How do you write a shell variable inside a recipe? *Double the dollar: `$$VAR`.*
5. What does `up: env` mean? *`env` runs before `up`.*

## Further reading

- GNU Make manual: https://www.gnu.org/software/make/manual/make.html
