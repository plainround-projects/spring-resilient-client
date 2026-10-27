# OSV-Scanner (dependency audit)

## What it is

A **dependency audit** compares the libraries a project uses (and the libraries those use) with a list of publicly known security problems. **OSV** (Open Source Vulnerabilities) is a free database of such problems. **OSV-Scanner** is Google's tool that reads a project's dependency list and reports matches.

## Why this repository uses it

The project rule is: "dependencies pinned and checked by the CI's audit step". A Spring service pulls in hundreds of libraries; nobody reads that list by hand.

## Where it appears

| Path | Role |
| --- | --- |
| `scripts/audit.sh` | runs the scanner in a container |
| `osv-scanner.toml` | the list of accepted findings (empty, with a comment explaining the format) |
| `Makefile` | `make audit` |
| `.github/workflows/ci.yml` | CI runs `make audit` |

## How it works underneath

1. The scanner reads `pom.xml`. Because the POM has the Spring Boot parent, the scanner resolves the parent and the managed versions to find the full set of libraries and versions.
2. For each library and version it asks the OSV database for known advisories.
3. It prints the matches and exits non-zero if there are any that are not accepted in `osv-scanner.toml`.
4. The tool needs network access to download advisories and parent POMs.

## Walkthrough of this repository's use

```bash
set -euo pipefail

cd "$(dirname "$0")/.."
IMAGE="${OSV_SCANNER_IMAGE:-ghcr.io/google/osv-scanner@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0}"
docker run --rm -v "$PWD:/src:ro" -w /src "$IMAGE" scan source --lockfile=pom.xml --config=osv-scanner.toml
```

- `IMAGE="${OSV_SCANNER_IMAGE:-ghcr.io/google/osv-scanner@sha256:...}"` takes the image from an environment variable or, by default, the version pinned by digest (OSV-Scanner 2.7.0). Pinning means a new, different scanner cannot change CI results without a commit.
- `docker run --rm -v "$PWD:/src:ro" -w /src "$IMAGE" scan source --lockfile=pom.xml --config=osv-scanner.toml`: run the scanner in a throwaway container; mount the project read-only at `/src`; `scan source` scans a source tree; `--lockfile=pom.xml` says "use this file as the dependency list"; `--config` names the accepted-findings file.

```toml
# Accepted findings go here, each with an id, an expiry and a reason. None are accepted today.
```

The config holds no accepted findings. If one were ever accepted, the file would carry its id, an expiry date and the reason.

## Try it yourself

1. `make audit` (needs Docker and network).
2. Read the exit code: `make audit; echo $?` is `0` when nothing is found.
3. Add an `[[IgnoredVulns]]` entry to a copy of `osv-scanner.toml` to see how a finding would be accepted (see the documentation for the keys).

## Mistakes people make

- Accepting a finding without a reason or an expiry date.
- Reading "no findings" as "no risk": the database only knows published problems.
- Using a scanner whose version changes silently.

## Check yourself

1. What does the audit compare? *The project's libraries with known vulnerabilities.*
2. Why pin the scanner image by digest? *Reproducible results.*
3. Where are accepted findings recorded? *`osv-scanner.toml`.*
4. Why does it need network access? *To fetch advisories and parent POMs.*
5. What is OSV? *An open database of vulnerabilities.*

## Further reading

- OSV-Scanner documentation: https://google.github.io/osv-scanner/
- OSV database: https://osv.dev/
