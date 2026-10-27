# YAML

## What it is

YAML is a text format for structured data that uses **indentation** instead of brackets. A file is made of **mappings** (`key: value`), **lists** (lines that start with `- `) and plain values (text, numbers, `true`, `false`). Lines starting with `#` are comments.

## Why this repository uses it

Docker Compose and GitHub Actions define their files in YAML. The repository follows the tools.

## Where it appears

| Path | What the YAML describes |
| --- | --- |
| `docker-compose.yml` | the container stack |
| `.github/workflows/ci.yml` | the continuous-integration job |
| `observability/dashboards.yaml` | where Grafana should look for dashboard files |

## How it works underneath

1. A parser reads the file and builds the same kind of tree XML has, but the nesting comes from the indentation: children are indented more than their parent, by spaces (never tabs).
2. `key: value` is a mapping entry. `key:` followed by indented lines means the value is a nested mapping. Lines beginning with `- ` form a list.
3. Strings need no quotes unless they contain special characters (`:` followed by a space, `#`, `{`). Quotes are used for values like `"18101:8080"` so the colon is not read as a mapping.
4. A **block scalar** (`|`) keeps line breaks of the text that follows; `>` folds them into one line.
5. A wrong indent changes the meaning or makes the parser fail; the error names a line.

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

- `name: ci` names the workflow. `on: [push, pull_request]` is a **list** of events that start it.
- `permissions:` and `contents: read` form a nested mapping: the job may only read the repository.
- `jobs:` holds one job, `check:`, with `runs-on: ubuntu-latest`, and `steps:` is a **list**: each `- uses: ...` or `- run: ...` is one step. `with:` under a step is a nested mapping of that step's inputs. Quotes around `"25"` keep it a string.

In `docker-compose.yml`, the same shapes: `services:` is a mapping of service names; `ports:` and `cap_drop:` are lists; `test: ["CMD", "bash", "-c", "..."]` is a list written inline with brackets.

## Try it yourself

1. `python3 -c "import yaml,sys; print(yaml.safe_load(open('docker-compose.yml')))"` (if PyYAML is installed) prints the tree as Python data.
2. `docker compose config` parses and expands the compose file, and errors on bad YAML.
3. Change the indent of one line in a scratch copy and run `docker compose config` again to see the error.

## Mistakes people make

- Tabs for indentation: not allowed.
- Unquoted values that look like something else: `18101:8080` can be read as a number in base 60 by old parsers; quote it.
- The word `on`, `yes` or `no` as a value: some parsers read them as booleans.
- Misaligned list items.

## Check yourself

1. How does YAML show nesting? *By indentation.*
2. How is a list written? *Lines starting with `- `.*
3. Why quote `"18101:8080"`? *So the colon is not parsed as a mapping.*
4. Can you indent with tabs? *No.*
5. What does `#` start? *A comment.*

## Further reading

- YAML 1.2.2 specification: https://yaml.org/spec/1.2.2/
