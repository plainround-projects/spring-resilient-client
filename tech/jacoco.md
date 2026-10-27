# JaCoCo (code coverage)

## What it is

**Coverage** says how much of the production code the tests ran. JaCoCo measures it: it attaches a **Java agent** (a program that watches the JVM) to the test run, records which lines were executed, and then produces a report and can check limits.

## Why this repository uses it

The project rule is "coverage reported, with the domain at or near full coverage". A limit in the build keeps it from sliding. Coverage is not proof of good tests; it only catches code no test touches.

## Where it appears

| Path | Role |
| --- | --- |
| `pom.xml` (root) and `price-service/pom.xml` | a rule for each module (85 percent) and, in `price-service`, for the `domain` package (95 percent) |
| `target/site/jacoco/index.html` | the HTML report, produced by `./mvnw verify` (not committed) |

## How it works underneath

1. The goal `prepare-agent` runs early and sets a Maven property `argLine` to `-javaagent:.../jacoco-agent.jar=destfile=target/jacoco.exec`. Surefire and Failsafe add that to the test JVM's command line (that is what `@{argLine}` is for in the POM).
2. While tests run, the agent notes every line that executes and writes `target/jacoco.exec` when the JVM stops. Unit tests and integration tests append to the same file.
3. The goal `report` (phase `verify`) turns the binary file into HTML and XML.
4. The goal `check` reads rules from the POM and fails the build, with a message, if a counter is below its minimum.
5. A **counter** counts a kind of thing (lines, branches, instructions). The **covered ratio** is covered over total.

## Walkthrough of this repository's use

```xml
  <artifactId>jacoco-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>check</id>
      <configuration>
        <rules>
          <rule>
            <element>PACKAGE</element>
            <includes>
              <include>*.domain</include>
            </includes>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>${jacoco.domain.minimum}</minimum>
              </limit>
            </limits>
          </rule>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>${jacoco.bundle.minimum}</minimum>
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- Three **executions**. `prepare-agent` is the agent step. `report` runs in `verify` and writes the report. `check` runs in `verify` and enforces the `<rules>`.
- A `<rule>` with `<element>PACKAGE</element>` and `<include>*.domain</include>` applies to the package whose name ends in `domain`; `<element>BUNDLE</element>` applies to the whole module.
- `<counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.95</minimum>` reads: at least 95 percent of lines covered.
- The limit values are properties at the top of the POM (`jacoco.domain.minimum`, `jacoco.bundle.minimum`) so they are easy to find. Changing them down to get a build through is against the house rules.

## Try it yourself

1. `./mvnw verify` and look near the end for `All coverage checks have been met.`
2. Open `target/site/jacoco/index.html` (in `price-service/` after the build) in a browser. Green lines ran; red lines did not.
3. Temporarily delete a test class in a scratch copy and run `./mvnw verify`: the check names the package that fell below its limit.

## Mistakes people make

- Chasing 100 percent with tests that assert nothing.
- Forgetting `@{argLine}` in Surefire: then no agent is attached and coverage reads 0.
- Excluding the awkward classes to pass the gate. This repository excludes nothing.

## Check yourself

1. What is a Java agent? *A program attached to the JVM at startup that can watch the code run.*
2. Which file holds the raw coverage data? *`target/jacoco.exec`.*
3. Which phase checks the limits? *`verify`.*
4. What does a 95 percent line limit on `domain` mean? *At most 5 percent of its lines may be unrun.*
5. Does 100 percent coverage prove the tests are good? *No.*

## Further reading

- JaCoCo Maven plugin (version 0.8.16): https://www.jacoco.org/jacoco/trunk/doc/maven.html
- Check goal rules: https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html
