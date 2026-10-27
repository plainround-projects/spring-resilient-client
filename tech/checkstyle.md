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

**The plugin settings in the POM.**

```xml
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>${checkstyle-plugin.version}</version>
  <dependencies>
    <dependency>
      <groupId>com.puppycrawl.tools</groupId>
      <artifactId>checkstyle</artifactId>
      <version>${checkstyle.version}</version>
    </dependency>
  </dependencies>
  <configuration>
    <configLocation>${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml</configLocation>
    <suppressionsLocation>${maven.multiModuleProjectDirectory}/config/checkstyle/suppressions.xml</suppressionsLocation>
    <includeTestSourceDirectory>true</includeTestSourceDirectory>
    <consoleOutput>true</consoleOutput>
    <failOnViolation>true</failOnViolation>
    <violationSeverity>warning</violationSeverity>
  </configuration>
</plugin>
```

- `<version>3.6.0</version>` is the Maven plugin; the nested `<dependency>` on `com.puppycrawl.tools:checkstyle:14.3.0` is the Checkstyle engine it runs. They are versioned separately.
- `<configLocation>` points at the rules file, written with `${maven.multiModuleProjectDirectory}`, the folder of the root POM, because every module is in a sub-folder. `<suppressionsLocation>` points at the suppressions file; the rules file reads it through the variable `${checkstyle.suppressions.file}`.
- `<includeTestSourceDirectory>true</includeTestSourceDirectory>`: test code is checked as well.
- `<consoleOutput>true</consoleOutput>` prints violations in the build output. `<failOnViolation>true</failOnViolation>` and `<violationSeverity>warning</violationSeverity>` make any violation of warning level or above fail the build.

**Every rule in `config/checkstyle/checkstyle.xml`.** The file is XML (see `xml.md`): each `<module name="...">` switches on one rule, and `<property name="..." value="..."/>` inside it changes its options. Here is every module in the file, with its options and what it checks:

| Rule | Settings in this file | What it checks |
| --- | --- | --- |
| `Checker` | `severity` = `warning` | The root of the rules: everything below applies to every file. Its `severity` property makes every violation a warning, and the plugin is set to fail on warnings. |
| `NewlineAtEndOfFile` | - | A file must end with a line break. |
| `FileTabCharacter` | - | No tab characters in Java files; spaces only. |
| `RegexpSingleline` | `format` = `\s+$`; `message` = `Trailing whitespace` | Fails a line that matches a pattern. Here the pattern is `\s+$`: spaces at the end of a line. |
| `TreeWalker` | (contains the rules below) | The group of rules that look inside each file's syntax tree (classes, methods, imports). |
| `PackageName` | - | Package names are lower case. |
| `TypeName` | - | Class, record and interface names start with a capital letter. |
| `MethodName` | - | Method names start with a lower-case letter (camelCase). |
| `ParameterName` | - | Parameter names are camelCase. |
| `LocalVariableName` | `allowOneCharVarInForLoop` = `false`; `format` = `^[a-z][a-zA-Z0-9]+$` | Local variable names are camelCase and at least two characters; one-letter names, loop counters included, are not allowed. |
| `MemberName` | - | Field names are camelCase. |
| `ConstantName` | - | `static final` constants are written in UPPER_CASE. |
| `ModifierOrder` | - | Modifiers come in the standard order (`public static final`). |
| `RedundantModifier` | - | No modifier that is already implied (for example `public` on an interface method). |
| `NeedBraces` | - | An `if`, `for` or `while` always uses braces, even for one statement. |
| `EmptyStatement` | - | No stray `;` on its own. |
| `EqualsHashCode` | - | A class that overrides `equals` must also override `hashCode`. |
| `MissingSwitchDefault` | - | A `switch` statement has a `default` branch. |
| `SimplifyBooleanExpression` | - | No `x == true` or `!(a == b)` style clutter. |
| `SimplifyBooleanReturn` | - | Write `return condition;` instead of `if (condition) return true; else return false;`. |
| `HideUtilityClassConstructor` | - | A class with only static methods must have a private constructor, so nobody creates an instance. |
| `UpperEll` | - | A long literal ends in `L`, not `l` (which looks like a one). |
| `ArrayTypeStyle` | - | Write `String[] names`, not `String names[]`. |
| `OneTopLevelClass` | - | One top-level class per file. |
| `OuterTypeFilename` | - | The file name equals the name of its top-level type. |
| `TodoComment` | `format` = `(TODO\|FIXME\|XXX)` | Fails a comment containing TODO, FIXME or XXX: no unfinished business is committed. |
| `RegexpSinglelineJava` | `format` = `System\.(out\|err)\.print`; `ignoreComments` = `true`; `message` = `Use the logger, not System.out or System.err` | Fails a Java code line (comments ignored) that matches a pattern. Used twice: one forbids `System.out`/`System.err` printing, one forbids `@Autowired`. |
| `RegexpSinglelineJava` | `format` = `@Autowired`; `ignoreComments` = `true`; `message` = `Constructor injection only: no @Autowired (a single constructor is enough)` | Fails a Java code line (comments ignored) that matches a pattern. Used twice: one forbids `System.out`/`System.err` printing, one forbids `@Autowired`. |
| `MissingJavadocType` | `scope` = `public`; `skipAnnotations` = `SpringBootApplication` | A public class, record or interface needs a Javadoc comment (`/** ... */`). |
| `MissingJavadocMethod` | `scope` = `public`; `allowMissingPropertyJavadoc` = `true`; `allowedAnnotations` = `Override, Test, ParameterizedTest, BeforeEach, AfterEach, BeforeAll, AfterAll, Bean`; `tokens` = `METHOD_DEF`; `minLineCount` = `3` | A public method of three lines or more needs Javadoc, except those with the listed annotations (such as `@Override` and `@Test`). |
| `SummaryJavadoc` | - | The first sentence of a Javadoc comment must be a real summary. |

Two of the rules take regular expressions. A **regular expression** is a pattern language for text: `\s+$` means "one or more whitespace characters at the end of a line". In this file `System\.(out|err)\.print` matches `System.out.print`, `System.out.println` and the same with `err`; `@Autowired` matches that word. The properties `ignoreComments=true` make the Java rules skip comment text, and `message` is what the build prints when the rule fires.

The two lists inside the rules are worth reading closely. `MissingJavadocMethod` lists `allowedAnnotations` (`Override, Test, ParameterizedTest, BeforeEach, AfterEach, BeforeAll, AfterAll, Bean`): methods with these labels need no Javadoc, since the label already says what they are. `minLineCount` 3 means a short method is exempt. `MissingJavadocType` skips the class annotated `SpringBootApplication`.

**Every suppression in `config/checkstyle/suppressions.xml`.**

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

Each `<suppress>` has `checks` (the rule names, separated by `|`), `files` (a regular expression over the file path) and optionally `message` (only violations with that text). The comment above each says why:

- `HideUtilityClassConstructor` for `*Application.java` under `src/main`: the entry point holds only a static `main` method.
- `HideUtilityClassConstructor|ConstantName` for `ArchitectureTest.java`: ArchUnit rules are `static final` fields with sentence names.
- `RegexpSinglelineJava` with the message "Constructor injection only" for files under `src/test`: Spring test classes receive test beans with `@Autowired`.
- `IllegalImport` for every file in the `provider` and `loadgen` modules: `com.sun.net.httpserver` is the supported public API of the JDK module `jdk.httpserver`; the `IllegalImport` rule bans the whole `com.sun` family.
- `RegexpSinglelineJava` with the message "Use the logger" for `JsonLog.java` in the provider: that class is the provider's logger and writes JSON lines with `System.out` on purpose.

### The complete rule and suppression files

`config/checkstyle/checkstyle.xml` lists the rules; `config/checkstyle/suppressions.xml` lists the places where a rule is switched off, each with a comment giving the reason. Both are quoted whole, each followed by an element-by-element reading.

### `config/checkstyle/checkstyle.xml`

The whole file, as it is in the repository:

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<!-- Rules that formatting cannot enforce: names, imports, public Javadoc, no leftovers. -->
<module name="Checker">
  <property name="severity" value="warning"/>
  <module name="SuppressionFilter">
    <property name="file" value="${checkstyle.suppressions.file}"/>
  </module>
  <property name="charset" value="UTF-8"/>

  <module name="NewlineAtEndOfFile"/>
  <module name="FileTabCharacter"/>
  <module name="RegexpSingleline">
    <property name="format" value="\s+$"/>
    <property name="message" value="Trailing whitespace"/>
  </module>

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

    <module name="ModifierOrder"/>
    <module name="RedundantModifier"/>
    <module name="NeedBraces"/>
    <module name="EmptyStatement"/>
    <module name="EqualsHashCode"/>
    <module name="MissingSwitchDefault"/>
    <module name="SimplifyBooleanExpression"/>
    <module name="SimplifyBooleanReturn"/>
    <module name="HideUtilityClassConstructor"/>
    <module name="UpperEll"/>
    <module name="ArrayTypeStyle"/>
    <module name="OneTopLevelClass"/>
    <module name="OuterTypeFilename"/>

    <module name="TodoComment">
      <property name="format" value="(TODO|FIXME|XXX)"/>
    </module>
    <module name="RegexpSinglelineJava">
      <property name="format" value="System\.(out|err)\.print"/>
      <property name="ignoreComments" value="true"/>
      <property name="message" value="Use the logger, not System.out or System.err"/>
    </module>
    <module name="RegexpSinglelineJava">
      <property name="format" value="@Autowired"/>
      <property name="ignoreComments" value="true"/>
      <property name="message" value="Constructor injection only: no @Autowired (a single constructor is enough)"/>
    </module>

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
    </module>
  </module>
</module>
```

Reading it, element by element (each line is one XML element, indented under the element that contains it):

- **`Checker`**: The root of the rules: everything below applies to every file. Its `severity` property makes every violation a warning, and the plugin is set to fail on warnings.
  - property `severity` = `warning`: a setting of the rule it sits in.
  - **`SuppressionFilter`**: Loads `suppressions.xml`, the list of places where a rule is switched off on purpose.
    - property `file` = `${checkstyle.suppressions.file}`: a setting of the rule it sits in.
  - property `charset` = `UTF-8`: a setting of the rule it sits in.
  - **`NewlineAtEndOfFile`**: A file must end with a line break.
  - **`FileTabCharacter`**: No tab characters in Java files; spaces only.
  - **`RegexpSingleline`**: Fails a line that matches a pattern. Here the pattern is `\s+$`: spaces at the end of a line.
    - property `format` = `\s+$`: a setting of the rule it sits in.
    - property `message` = `Trailing whitespace`: a setting of the rule it sits in.
  - **`TreeWalker`**: The group of rules that look inside each file's syntax tree (classes, methods, imports).
    - **`AvoidStarImport`**: No `import java.util.*;`; every import names one class.
    - **`UnusedImports`**: An import that nothing uses is an error.
    - **`RedundantImport`**: No duplicate imports, no imports of the same package or of `java.lang`.
    - **`IllegalImport`**: Bans imports from the listed packages (`sun`, `com.sun`, `org.apache.commons.logging`); they are internal or the wrong logging API.
      - property `illegalPkgs` = `sun, com.sun, org.apache.commons.logging`: a setting of the rule it sits in.
    - **`PackageName`**: Package names are lower case.
    - **`TypeName`**: Class, record and interface names start with a capital letter.
    - **`MethodName`**: Method names start with a lower-case letter (camelCase).
    - **`ParameterName`**: Parameter names are camelCase.
    - **`LocalVariableName`**: Local variable names are camelCase and at least two characters; one-letter names, loop counters included, are not allowed.
      - property `allowOneCharVarInForLoop` = `false`: a setting of the rule it sits in.
      - property `format` = `^[a-z][a-zA-Z0-9]+$`: a setting of the rule it sits in.
    - **`MemberName`**: Field names are camelCase.
    - **`ConstantName`**: `static final` constants are written in UPPER_CASE.
    - **`ModifierOrder`**: Modifiers come in the standard order (`public static final`).
    - **`RedundantModifier`**: No modifier that is already implied (for example `public` on an interface method).
    - **`NeedBraces`**: An `if`, `for` or `while` always uses braces, even for one statement.
    - **`EmptyStatement`**: No stray `;` on its own.
    - **`EqualsHashCode`**: A class that overrides `equals` must also override `hashCode`.
    - **`MissingSwitchDefault`**: A `switch` statement has a `default` branch.
    - **`SimplifyBooleanExpression`**: No `x == true` or `!(a == b)` style clutter.
    - **`SimplifyBooleanReturn`**: Write `return condition;` instead of `if (condition) return true; else return false;`.
    - **`HideUtilityClassConstructor`**: A class with only static methods must have a private constructor, so nobody creates an instance.
    - **`UpperEll`**: A long literal ends in `L`, not `l` (which looks like a one).
    - **`ArrayTypeStyle`**: Write `String[] names`, not `String names[]`.
    - **`OneTopLevelClass`**: One top-level class per file.
    - **`OuterTypeFilename`**: The file name equals the name of its top-level type.
    - **`TodoComment`**: Fails a comment containing TODO, FIXME or XXX: no unfinished business is committed.
      - property `format` = `(TODO|FIXME|XXX)`: a setting of the rule it sits in.
    - **`RegexpSinglelineJava`**: Fails a Java code line (comments ignored) that matches a pattern. Used twice: one forbids `System.out`/`System.err` printing, one forbids `@Autowired`.
      - property `format` = `System\.(out|err)\.print`: a setting of the rule it sits in.
      - property `ignoreComments` = `true`: a setting of the rule it sits in.
      - property `message` = `Use the logger, not System.out or System.err`: a setting of the rule it sits in.
    - **`RegexpSinglelineJava`**: Fails a Java code line (comments ignored) that matches a pattern. Used twice: one forbids `System.out`/`System.err` printing, one forbids `@Autowired`.
      - property `format` = `@Autowired`: a setting of the rule it sits in.
      - property `ignoreComments` = `true`: a setting of the rule it sits in.
      - property `message` = `Constructor injection only: no @Autowired (a single constructor is enough)`: a setting of the rule it sits in.
    - **`MissingJavadocType`**: A public class, record or interface needs a Javadoc comment (`/** ... */`).
      - property `scope` = `public`: a setting of the rule it sits in.
      - property `skipAnnotations` = `SpringBootApplication`: a setting of the rule it sits in.
    - **`MissingJavadocMethod`**: A public method of three lines or more needs Javadoc, except those with the listed annotations (such as `@Override` and `@Test`).
      - property `scope` = `public`: a setting of the rule it sits in.
      - property `allowMissingPropertyJavadoc` = `true`: a setting of the rule it sits in.
      - property `allowedAnnotations` = `Override, Test, ParameterizedTest, BeforeEach, AfterEach, BeforeAll, AfterAll, Bean`: a setting of the rule it sits in.
      - property `tokens` = `METHOD_DEF`: a setting of the rule it sits in.
      - property `minLineCount` = `3`: a setting of the rule it sits in.
    - **`SummaryJavadoc`**: The first sentence of a Javadoc comment must be a real summary.


### `config/checkstyle/suppressions.xml`

The whole file, as it is in the repository:

```xml
<?xml version="1.0"?>
<!DOCTYPE suppressions PUBLIC "-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN"
    "https://checkstyle.org/dtds/suppressions_1_2.dtd">
<!-- Each suppression says why. Nothing here hides a production-code problem. -->
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

Reading it, element by element (each line is one XML element, indented under the element that contains it):

- **`<suppressions>`**: the list of places where a rule is deliberately switched off.
  - **suppress** `HideUtilityClassConstructor` for files matching `[\\/]src[\\/]main[\\/].*Application\.java`: switches those checks off for those files; the comment above it says why.
  - **suppress** `HideUtilityClassConstructor|ConstantName` for files matching `[\\/]ArchitectureTest\.java`: switches those checks off for those files; the comment above it says why.
  - **suppress** `RegexpSinglelineJava` for files matching `[\\/]src[\\/]test[\\/]` (only messages matching `Constructor injection only`): switches those checks off for those files; the comment above it says why.
  - **suppress** `IllegalImport` for files matching `[\\/](provider|loadgen)[\\/].*\.java`: switches those checks off for those files; the comment above it says why.
  - **suppress** `RegexpSinglelineJava` for files matching `[\\/]provider[\\/].*JsonLog\.java` (only messages matching `Use the logger`): switches those checks off for those files; the comment above it says why.


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
