# Git and .gitignore

## What it is

Git is a version control tool. It stores the history of a project as a chain of **commits**: each commit is a snapshot of the files plus a message that says why. A **repository** is the folder with that history (in a hidden `.git` folder). A `.gitignore` file lists names that Git must never record.

## Why this repository uses it

Each project is its own Git repository so that it can be pushed to GitHub on its own. The commits are small and each has a message that says what kind of change it is (`build:`, `feat:`, `test:`, `ci:`, `docs:`).

## Where it appears

| Path | Role |
| --- | --- |
| `.git/` | the history (not a file you edit) |
| `.gitignore` | names Git must skip |
| `.dockerignore` | a similar list, for what Docker must not copy into an image |

## How it works underneath

1. `git add file` puts a file's current content in the **staging area**. `git commit` turns the staging area into a commit with a message.
2. A commit points to its parent, so history is a chain. `git log --oneline` prints it.
3. A pattern in `.gitignore` hides matching files from `git status`, so `git add .` skips them. It affects only files not yet tracked.
4. Build output, local settings and secrets are never committed: they can be recreated, they differ per machine, or they must not be public.

## Walkthrough of this repository's use

```text
# build output and local state
target/
.env
logs/
*.log
.DS_Store

# IDEs
.idea/
*.iml
.vscode/
.classpath
.project
.settings/

# tooling
.omc/
.mvn/wrapper/*.jar
```

- `target/` is Maven's build output. It is rebuilt from the source.
- `.env` holds local settings (). The committed `.env.example` shows the names without values.
- `logs/`, `*.log` and `.DS_Store` are local noise (the last is a macOS folder file).
- `.idea/`, `*.iml`, `.vscode/`, `.classpath`, `.project`, `.settings/` are editor and IDE files.
- `.omc/` is a local tooling folder.
- `.mvn/wrapper/*.jar` is a wrapper jar that older wrapper versions download; the repository uses the script-only wrapper, so none is committed.

The commit history of this repository, in order:

1. `build:` the Maven project with wrapper, formatter, linter and coverage gates
2. `feat:` the code
3. `test:` the tests
4. `ci:` the Dockerfile, compose file, Makefile, scripts and workflow
5. `docs:` README, ARCHITECTURE, CHANGELOG, decision records

## Try it yourself

1. `git log --oneline` prints the commits.
2. `git status` prints nothing when the tree is clean. Run `./mvnw compile` first: it still prints nothing, because `target/` is ignored.
3. `git check-ignore -v target/` names the `.gitignore` line that hides it.
4. `git ls-files | head` lists only tracked files.

## Mistakes people make

- Committing a secret: deleting it later does not remove it from history.
- Adding a name to `.gitignore` after the file was committed: it stays tracked until you run `git rm --cached`.
- Committing build output and then fighting merge conflicts in it.

## Check yourself

1. What is a commit? *A snapshot plus a message, linked to its parent.*
2. Does `.gitignore` remove a file already tracked? *No.*
3. Why is `.env` ignored but `.env.example` committed? *The first may hold secrets; the second shows names only.*
4. What does `git status` show on a clean tree? *Nothing to commit.*
5. Why are commit messages prefixed (`feat:`, `docs:`)? *To state the kind of change at a glance.*

## Further reading

- Pro Git book: https://git-scm.com/book/en/v2
- gitignore reference: https://git-scm.com/docs/gitignore
