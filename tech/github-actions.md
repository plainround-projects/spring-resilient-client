# GitHub Actions

## What it is

GitHub Actions is GitHub's built-in automation. A **workflow** is a YAML file under `.github/workflows/`. GitHub runs it on a fresh virtual machine (a **runner**) whenever an **event** you named happens (a push, a pull request). A workflow has **jobs**, a job has ordered **steps**, and a step either runs a shell command (`run:`) or uses a prepackaged **action** (`uses:`).

## Why this repository uses it

The project rule is that CI runs format, lint, tests and the audit on every push, with the same commands the reader runs locally. The workflow therefore calls `make` targets and nothing else, so CI and your laptop cannot drift apart.

## Where it appears

| Path | Role |
| --- | --- |
| `.github/workflows/ci.yml` | the whole workflow |

## How it works underneath

1. A push or pull request makes GitHub read `ci.yml` and start a job on an `ubuntu-latest` runner.
2. Every job starts from an empty machine. `actions/checkout` downloads the repository into it. `actions/setup-java` installs a JDK and, with `cache: maven`, restores and saves the Maven download folder between runs.
3. The steps run in order; the first failing step fails the job and the commit gets a red mark.
4. `permissions: contents: read` gives the job's token only read access to the repository: least privilege.
5. The runner already has Docker, which is why `make audit` and `make docker-build` work there.

## Walkthrough of this repository's use

```yaml
name: ci

on: [push, pull_request]

permissions:
  contents: read

jobs:
  check:
    name: Format, lint, tests with coverage, dependency audit, image build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "25"
          cache: maven
      - run: make lint
      - run: make test
      - run: make audit
      - run: make docker-build
```

- `on: [push, pull_request]`: start on every push and on every pull request.
- `permissions: contents: read`: the least the job needs.
- `runs-on: ubuntu-latest`: the runner image.
- `actions/checkout@v5`: gets the code. `@v5` pins a major version of the action.
- `actions/setup-java@v5` with `distribution: temurin` and `java-version: "25"`: the Eclipse Temurin JDK 25, the same as the Dockerfile. `cache: maven` caches `~/.m2`.
- `run: make lint`, `make test`, `make audit`, `make docker-build`: the same commands as in the README. `make test` runs `./mvnw verify`, which needs no Docker.

## Try it yourself

1. Read the workflow: `cat .github/workflows/ci.yml`.
2. Run what CI runs, locally: `make lint && make test`.
3. After you push to GitHub, open the repository's "Actions" tab to watch a run.
4. `act` (a separate open-source tool) can run workflows locally in Docker; this repository does not depend on it.

## Mistakes people make

- Putting logic in the workflow that exists nowhere else: then CI differs from local runs.
- Giving the token write access it does not need.
- Forgetting that every run starts from an empty machine (nothing from a previous run exists unless cached).
- Using an action by branch name (`@main`) so it can change under you; pin a version.

## Check yourself

1. What starts a workflow? *An event such as a push.*
2. What is a step? *One command or one action inside a job.*
3. Why call `make` from the workflow? *CI and local runs share the same commands.*
4. What does `contents: read` do? *Limits the token to reading the repository.*
5. What does `cache: maven` save? *The downloaded libraries between runs.*

## Further reading

- GitHub Actions documentation: https://docs.github.com/en/actions
- Workflow syntax: https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
