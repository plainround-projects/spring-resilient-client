# Checkstyle

## What it is

Checkstyle is a **linter** for Java: it reads source files and reports places that break a list of rules. Rules are written in an XML file. Where a formatter changes layout, Checkstyle checks content: naming, missing documentation, forbidden calls.

## Why this repository uses it

The project rules ask for Javadoc on the public surface, no leftover TODOs, no `System.out`, and constructor injection only. Checkstyle makes each of those a failing build step.

## Where it appears

| Path | Role |
| --- | --- |
| `config/checkstyle/checkstyle.xml` | the rules |
| `config/checkstyle/suppressions.xml` | the few switched-off checks, each with a reason |
| `pom.xml` | the `maven-checkstyle-plugin` and the Checkstyle version |
| `Makefile` | `make lint` |

## How it works underneath

1. `./mvnw checkstyle:check` loads the rules, parses every `.java` file (production and test, because `includeTestSourceDirectory` is true) and counts violations.
2. Each rule has a **severity**. This repository sets the checker's severity to `warning` and tells the plugin `violationSeverity=warning`, so any violation fails the build.
3. A **suppression** hides one check for some files or messages. Suppressions are visible and carry a comment.
4. The Checkstyle engine version is set by a plugin dependency (14.3.0), separately from the Maven plugin version (3.6.0).

## Walkthrough of this repository's use

```xml
<module name="TreeWalker">
  <module name="AvoidStarImport"/>
  <module name="UnusedImports"/>
  <module name="RedundantImport"/>
  <module name="IllegalImport">
    <property name="illegalPkgs" value="sun, com.sun, org.apache.commons.logging"/>
  </module>

  <module name="PackageName"/>
  <module name="TypeName"/>
  <module name="MethodName"/>
  <module name="ParameterName"/>
  <module name="LocalVariableName">
    <property name="allowOneCharVarInForLoop" value="false"/>
    <property name="format" value="^[a-z][a-zA-Z0-9]+$"/>
  </module>
  <module name="MemberName"/>
  <module name="ConstantName"/>
```

`TreeWalker` is the group of rules that look inside each file's syntax tree. `AvoidStarImport` and `UnusedImports` keep imports tidy. `IllegalImport` bans some packages. The naming rules (`TypeName`, `MethodName`, `MemberName`, `ConstantName`) require Java conventions. `LocalVariableName` is set so a local variable has at least two characters.

```xml
<module name="TodoComment">
  <property name="format" value="(TODO|FIXME|XXX)"/>
</module>
```

`TodoComment` forbids `TODO`, `FIXME`, `XXX`: no unfinished business is committed.

```xml
<module name="RegexpSinglelineJava">
  <property name="format" value="System\.(out|err)\.print"/>
  <property name="ignoreComments" value="true"/>
  <property name="message" value="Use the logger, not System.out or System.err"/>
</module>
```

`RegexpSinglelineJava` fails a line that matches a regular expression: here any `System.out.print` or `System.err.print` outside comments, with a message that says what to do instead. A second rule does the same for `@Autowired`.

```xml
<module name="MissingJavadocType">
  <property name="scope" value="public"/>
  <property name="skipAnnotations" value="SpringBootApplication"/>
</module>
<module name="MissingJavadocMethod">
  <property name="scope" value="public"/>
  <property name="allowMissingPropertyJavadoc" value="true"/>
  <property name="allowedAnnotations" value="Override, Test, ParameterizedTest, BeforeEach, AfterEach, BeforeAll, AfterAll, Bean"/>
  <property name="tokens" value="METHOD_DEF"/>
  <property name="minLineCount" value="3"/>
</module>
<module name="SummaryJavadoc">
```

Public classes and public methods of three lines or more need Javadoc. Methods marked `@Override`, `@Test` and a few others are exempt. `SummaryJavadoc` checks the first sentence is a real summary.

The suppressions file lists every exception:

```xml
<suppressions>
  <!-- A Spring Boot entry point holds only a static main method. -->
  <suppress checks="HideUtilityClassConstructor" files="[\\/]src[\\/]main[\\/].*Application\.java"/>
  <!-- ArchUnit rules are static final fields named for the rule they state, and the class holds nothing else. -->
  <suppress checks="HideUtilityClassConstructor|ConstantName" files="[\\/]ArchitectureTest\.java"/>
  <!-- Test classes receive Spring test beans (MockMvc, repositories) with @Autowired; production code never does. -->
  <suppress checks="RegexpSinglelineJava" message="Constructor injection only" files="[\\/]src[\\/]test[\\/]"/>
  <!-- com.sun.net.httpserver is the supported public API of the jdk.httpserver module, which the plain-Java provider and its tests use on purpose. -->
  <suppress checks="IllegalImport" files="[\\/](provider|loadgen)[\\/].*\.java"/>
  <!-- JsonLog is the provider's logger: one JSON object per line on standard output, with no logging framework. -->
  <suppress checks="RegexpSinglelineJava" message="Use the logger" files="[\\/]provider[\\/].*JsonLog\.java"/>
</suppressions>
```

Each `<suppress>` names the check, the files and (for one) the message, and a comment above it says why. For example, `ArchitectureTest` holds ArchUnit rules as `static final` fields with sentence names, which breaks the constant-name rule on purpose. The provider module uses the JDK's own `com.sun.net.httpserver` (a supported public API of the `jdk.httpserver` module) and writes its JSON log lines with `System.out`, so those two checks are switched off for that module only.

## Try it yourself

1. `./mvnw checkstyle:check` prints `You have 0 Checkstyle violations.`
2. Add `System.out.println("x");` to a class under `src/main` in a scratch copy and run it again: one violation with the message "Use the logger, not System.out or System.err".
3. Remove the Javadoc from a public method with three or more lines and run it again.
4. `make lint` runs both Spotless and Checkstyle.

## Mistakes people make

- Fixing a violation by adding a suppression: the suppression file here is short and justified.
- Mixing up format and lint: Spotless fixes layout; Checkstyle only reports.
- Ignoring the warning in the console because the build still "ran": here warnings fail the build.

## Check yourself

1. What is a linter? *A tool that reports rule violations in source code.*
2. How does a Checkstyle rule fail the build here? *The plugin treats warnings as failures.*
3. What is a suppression for? *To switch a rule off for a stated reason.*
4. Which rule keeps `@Autowired` out? *A `RegexpSinglelineJava` with a message.*
5. Which files does Checkstyle read? *Production and test sources.*

## Further reading

- Checkstyle (version 14.3.0): https://checkstyle.org/
- Maven Checkstyle Plugin (3.6.0): https://maven.apache.org/plugins/maven-checkstyle-plugin/
