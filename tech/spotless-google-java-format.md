# Spotless and google-java-format

## What it is

A **formatter** rewrites source code into one agreed layout: where braces go, how long a line may be, how arguments wrap. **google-java-format** is a formatter for Java. **Spotless** is a Maven plugin that runs formatters over the files and can either check ("is every file already formatted?") or apply ("rewrite the files").

## Why this repository uses it

The rule is "a formatter and a linter configured in the repository and run in CI". With one format, pull requests show only real changes and nobody argues about style.

## Where it appears

| Path | Role |
| --- | --- |
| `pom.xml` | the `spotless-maven-plugin` configuration (root POM, inherited by every module) |
| `.mvn/jvm.config` | JVM options that the formatter needs on newer JDKs |
| `Makefile` | `make lint` runs `spotless:check`; `make format` runs `spotless:apply` |
| `.github/workflows/ci.yml` | CI runs `make lint` |

## How it works underneath

1. `./mvnw spotless:check` reads every `.java` file, runs google-java-format on its text in memory, and compares with the file. Any difference fails the build and lists the files.
2. `./mvnw spotless:apply` writes the formatted text back to the files.
3. google-java-format parses the code with the Java compiler's own parser. That is why `.mvn/jvm.config` opens compiler packages with `--add-exports`: a newer JDK hides them by default.
4. Spotless downloads the formatter from Maven Central the first time and caches it.
5. The `AOSP` style of google-java-format uses 4-space indentation (the default Google style uses 2).

## Walkthrough of this repository's use

```xml
  <artifactId>spotless-maven-plugin</artifactId>
  <version>${spotless.version}</version>
  <configuration>
    <java>
      <googleJavaFormat>
        <version>${google-java-format.version}</version>
        <style>AOSP</style>
        <reflowLongStrings>true</reflowLongStrings>
      </googleJavaFormat>
      <removeUnusedImports/>
      <importOrder/>
    </java>
  </configuration>
</plugin>
```

- `<googleJavaFormat><version>...` pins the formatter version to **1.30.0**. Version 1.28 refuses to run on JDK 25 ("requires at least 1.30.0") and 1.37 failed here for a missing dependency, so the pin is a decision, not an accident.
- `<style>AOSP</style>` selects the 4-space style.
- `<reflowLongStrings>true</reflowLongStrings>` lets the formatter break a long string literal over lines.
- `<removeUnusedImports/>` deletes imports nothing uses. `<importOrder/>` sorts them.

Spotless applies the steps in the order they are written: first google-java-format, then the unused-import removal and the import sorting, so a file ends up both formatted and tidy.

## Try it yourself

1. `./mvnw spotless:check` prints `BUILD SUCCESS` when every file is formatted.
2. Add a few extra spaces inside a method of a scratch copy and run it again: the build fails and names the file.
3. `./mvnw spotless:apply` fixes it; `git diff` shows exactly what moved.
4. `make lint` runs Spotless and Checkstyle together.

## Mistakes people make

- Formatting by hand and disagreeing with the tool: run `spotless:apply`, do not argue.
- Committing without running it: CI fails on `spotless:check`.
- Running with an old formatter on a new JDK (the `jvm-version` error).
- Expecting Spotless to find bugs: it only changes layout.

## Check yourself

1. What is the difference between `check` and `apply`? *`check` fails if a file differs; `apply` rewrites it.*
2. Why does `.mvn/jvm.config` exist? *The formatter uses compiler internals that newer JDKs hide.*
3. What does `<removeUnusedImports/>` do? *Deletes unused imports.*
4. Why pin the formatter version? *Different versions format differently, and old ones fail on new JDKs.*
5. What does AOSP style change? *Indentation is 4 spaces.*

## Further reading

- Spotless Maven plugin (3.10.4): https://github.com/diffplug/spotless/tree/main/plugin-maven
- google-java-format (1.30.0): https://github.com/google/google-java-format
