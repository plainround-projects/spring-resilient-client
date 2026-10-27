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

The first line is a comment listing the main targets. `SHELL := /bin/bash` makes recipes run in bash (the default `sh` lacks some features). `MVN := ./mvnw -B -ntp` stores the Maven command with its flags once (`-B` batch mode, `-ntp` no download progress). `PORT ?=` lines are defaults. `-include .env` and `export` bring `.env` values in.

```make
help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

env:
```

The `help` target prints every target that has a `## comment` after its name: `grep` selects those lines, `sed` replaces `:.*## ` with a tab, `sort` orders them. That is why `make help` lists all targets with one-line descriptions.

```make
build: ## package all modules (the load generator is run from its jar)
	$(MVN) -DskipTests package

up: env ## build both images, start the provider, the price service and the Grafana stack, wait until healthy
	docker compose up -d --build --wait

run: build ## the lesson's scenario step by step against the running stack (make up first)
	scripts/scenario.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)

benchmark: build ## the before/after table: every provider mode, 100 requests a second (make up first, about 6 minutes)
```

- `build` packages all modules (`-DskipTests package`), because the load generator is run from its jar.
- `up: env` creates `.env` and runs `docker compose up -d --build --wait` for the provider, the price service and the Grafana stack.
- `run: build` first builds, then runs `scripts/scenario.sh` against the running stack. A prerequisite is how one target says "do that first".

```make
test: ## every module's unit, slice and integration tests, then the coverage gates
	$(MVN) verify

test-unit: ## unit and slice tests only
	$(MVN) test

lint: ## Spotless format check and Checkstyle on every module
```

`test` runs `./mvnw verify`: unit tests, integration tests and the coverage gates. `test-unit` runs only the fast tests.

```make
lint: ## Spotless format check and Checkstyle on every module
	$(MVN) spotless:check checkstyle:check

format: ## apply the Spotless formatting
```

`lint` runs both style tools (see `spotless-google-java-format.md` and `checkstyle.md`).

```make
down: ## stop the containers
	docker compose down -v --remove-orphans

clean: down
	$(MVN) clean
```

`down` stops the containers (`-v` also deletes their data volumes; `--remove-orphans` removes leftovers). `clean` depends on `down` and then deletes `target/` through Maven.

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
