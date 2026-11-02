# Maven and the Maven wrapper

## What it is

Maven is a build tool for Java. You describe the project in a file called `pom.xml` (POM means Project Object Model): its name, its **dependencies** (other people's libraries it needs) and its **plugins** (small programs that do jobs such as compiling or testing). Maven downloads the dependencies from a public server called **Maven Central**, keeps copies in a folder on your disk (`~/.m2/repository`), and runs the jobs in a fixed order.

The **wrapper** (`mvnw`) is a small script checked into the repository. The first time you run it, it downloads the exact Maven version the project asks for. Nobody has to install Maven or worry about versions.

## Why this repository uses it

The lesson asks for a standard Java build with the wrapper checked in. One command, `./mvnw verify`, then compiles, runs every test, measures coverage and checks the coverage limits. CI and `make test` run the same command.

## Where it appears

| Path | What it does with Maven |
| --- | --- |
| `pom.xml` | the project description: parent, Java version, dependencies, plugins (this root file is the parent of the three modules) |
| `provider/pom.xml`, `loadgen/pom.xml`, `price-service/pom.xml` | one build description per module |
| `mvnw`, `mvnw.cmd` | the wrapper scripts for Mac or Linux and for Windows |
| `.mvn/wrapper/maven-wrapper.properties` | which Maven version the wrapper downloads |
| `.mvn/jvm.config` | extra options for the JVM that runs Maven itself |

## How it works underneath

A **library** is code written by someone else that your program uses; in Java it is shipped as a **jar** (a zip file of compiled classes). The **classpath** is the list of jars and folders Java searches for classes when it compiles or runs. A **repository** here means a server of jars (Maven Central) or the cache of downloaded jars on your disk (`~/.m2`), not a Git repository. A **goal** is one task a plugin can perform, written `plugin:goal` (for example `spotless:check`).

1. You run `./mvnw verify`. The script reads `.mvn/wrapper/maven-wrapper.properties`, downloads that Maven version into `~/.m2/wrapper` if it is not there yet, and runs it.
2. Maven reads `pom.xml`. If the file has a `<parent>`, Maven first reads the parent's POM and inherits its settings.
3. Maven works through a **lifecycle**: a fixed list of **phases**. The ones that matter here, in order: `validate`, `compile`, `test`, `package`, `verify`. Asking for a phase runs every phase before it.
4. Plugins attach to phases. `maven-compiler-plugin` runs in `compile`, `maven-surefire-plugin` runs the unit tests in `test`, `maven-failsafe-plugin` runs the integration tests after `package`, and `jacoco-maven-plugin` checks coverage in `verify`.
5. Output goes to the `target/` folder. It is rebuilt from the source, so it is never committed.
6. A **dependency** has coordinates: a `groupId`, an `artifactId` and a `version`. A **scope** says where it is needed: `compile` (default), `runtime`, or `test` (only for tests).

## Walkthrough of this repository's use

**The wrapper.** The script `mvnw` is a shell script (see `bash.md`) that reads one properties file:

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

- `wrapperVersion=3.3.4` is the version of the wrapper scripts themselves.
- `distributionType=only-script` means the wrapper does not carry a `.jar` file in the repository; the script does the download itself with tools every system has.
- `distributionUrl=...apache-maven-3.9.11-bin.zip` is the exact Maven that gets downloaded (3.9.11), so every machine and CI use the same one. `mvnw.cmd` is the same script for Windows.

**The JVM options for Maven itself.** The file `.mvn/jvm.config` has one option per line, and Maven passes them to the Java process that runs Maven:

```properties
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
```

The Spotless plugin formats code with a formatter that reads the Java compiler's internal classes. Since Java 16 those internals are hidden unless you open them. `--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` says: let all code (`ALL-UNNAMED`) use the package `com.sun.tools.javac.api` of the module `jdk.compiler`. There is one line for each of the six internal packages the formatter touches: `api`, `code`, `file`, `parser`, `tree`, `util`. Without the file the formatter fails with `InvocationTargetException`.

**The POM, from the top.** The first lines (`<?xml ...?>`, `<project ...>`, `<modelVersion>`) are explained in `xml.md`.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.2</version>
  <relativePath/>
</parent>
```

The root POM inherits from Spring Boot's parent (tested library versions, default plugin settings, UTF-8 source encoding; `<relativePath/>` tells Maven to download the parent). The three module POMs inherit from the root, so a plugin version is written once and applies everywhere.

```xml
<groupId>dev.plainround</groupId>
<artifactId>spring-resilient-client</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>
<name>spring-resilient-client</name>
<description>A price service that stays fast and honest while its provider is slow, failing or silent.</description>

<modules>
```

Coordinates, then `<packaging>pom</packaging>`: this project builds no jar of its own; it only groups modules. The list:

```xml
<modules>
  <module>provider</module>
  <module>loadgen</module>
  <module>price-service</module>
</modules>
```

Maven works out the build order from the dependencies between modules: `provider` first, because `price-service` uses it in its tests.

**Properties.** `<properties>` holds named values reused below:

```xml
<properties>
  <java.version>25</java.version>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <archunit.version>1.5.1</archunit.version>
  <spotless.version>3.10.4</spotless.version>
  <google-java-format.version>1.30.0</google-java-format.version>
  <checkstyle-plugin.version>3.6.0</checkstyle-plugin.version>
  <checkstyle.version>14.3.0</checkstyle.version>
  <jacoco.version>0.8.16</jacoco.version>
  <jacoco.bundle.minimum>0.85</jacoco.bundle.minimum>
</properties>
```

- `java.version` (25) is read by Spring Boot's parent and passed to the compiler as `maven.compiler.release`.
- `project.build.sourceEncoding` says source files are UTF-8.
- `archunit.version`, `spotless.version`, `google-java-format.version`, `checkstyle-plugin.version`, `checkstyle.version` and `jacoco.version` pin tools that Spring Boot's parent does not manage.
- `jacoco.bundle.minimum` (0.85) is the coverage limit for every module; `price-service/pom.xml` adds `jacoco.domain.minimum` (0.95) for its `domain` package.

**Dependencies.** A dependency is a library the project uses. Every one is listed here. A **scope** says where it is needed: `compile` (the default: to build and run), `runtime` (only when running, not to compile), `test` (only for tests; it is not in the final application). Spring Boot **starters** (`spring-boot-starter-...`) are bundles: one line pulls in a library and its usual companions. There is no `<version>` on most because the parent decides.

**price-service**

| Dependency | Scope | What it brings |
| --- | --- | --- |
| `spring-boot-starter-webmvc` | compile | Tomcat (the embedded web server), Spring Web MVC (controllers) and Jackson (JSON) |
| `spring-boot-starter-restclient` | compile | `RestClient`, the HTTP client the price service uses to call the provider |
| `spring-boot-starter-validation` | compile | Bean Validation (Hibernate Validator): `@NotBlank`, `@Min`, `@Valid` and friends |
| `spring-boot-starter-actuator` | compile | the health, beans and metrics endpoints |
| `spring-boot-starter-opentelemetry` | compile | tracing with OpenTelemetry and the OTLP export of traces and metrics |
| `micrometer-registry-prometheus` | compile | the `/actuator/prometheus` text format |
| `provider` | test | the fake provider module, used in the price service's tests as a real in-process server |
| `spring-boot-starter-test` | test | JUnit, AssertJ, Mockito, JsonPath and Spring's test support |
| `spring-boot-starter-webmvc-test` | test | `@WebMvcTest`, `MockMvc` and `@MockitoBean` for web-layer tests |
| `archunit-junit5` | test | ArchUnit rules that run as JUnit tests |

**provider** (plain Java; its tests need only JUnit and AssertJ)

| Dependency | Scope | What it brings |
| --- | --- | --- |
| `junit-jupiter` | test | the JUnit test framework (this module has no Spring) |
| `assertj-core` | test | AssertJ assertions (this module has no Spring) |

**loadgen** (plain Java; the same two test libraries)

| Dependency | Scope | What it brings |
| --- | --- | --- |
| `junit-jupiter` | test | the JUnit test framework (this module has no Spring) |
| `assertj-core` | test | AssertJ assertions (this module has no Spring) |

**Plugins.** Every plugin, each doing one job in the lifecycle:

**Root POM (applies to all modules)**

| Plugin | What it does here |
| --- | --- |
| `maven-compiler-plugin` | runs the Java compiler with the strict settings |
| `maven-surefire-plugin` | runs the unit tests (`*Test`) |
| `maven-failsafe-plugin` | runs the integration tests (`*IT`) and checks their result |
| `jacoco-maven-plugin` | measures coverage and enforces the minimums |
| `spotless-maven-plugin` | formats the Java files and checks the format |
| `maven-checkstyle-plugin` | runs the Checkstyle rules |

**price-service** adds `spring-boot-maven-plugin` (its `repackage` goal makes the runnable jar and leaves the `provider` test jar out of it). **provider** and **loadgen** add `maven-jar-plugin`, which writes the class named by the property `start-class` into the jar's manifest so `java -jar` knows where to start.

The compiler plugin:

```xml
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <parameters>true</parameters>
    <showWarnings>true</showWarnings>
    <compilerArgs>
      <arg>-Xlint:all,-processing</arg>
      <arg>-Werror</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

`<parameters>true</parameters>` keeps the names of method parameters in the bytecode, which Spring uses to report errors by parameter name. `<showWarnings>true</showWarnings>` prints warnings. The two `<arg>` lines are javac options: `-Xlint:all,-processing` turns on every compiler warning except the annotation-processing ones, and `-Werror` makes any warning fail the build.

Surefire and failsafe both get an `<argLine>`:

```xml
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>@{argLine} -XX:+EnableDynamicAgentLoading -Xshare:off</argLine>
  </configuration>
</plugin>
```

`@{argLine}` is a **late placeholder**: JaCoCo sets the property `argLine` to the option that attaches its coverage agent, and Surefire fills it in when it starts the test JVM (a plain `${argLine}` would be read too early and lose the agent). `-XX:+EnableDynamicAgentLoading` silences a JDK warning about the Mockito agent. `-Xshare:off` avoids a JDK warning that appears when an agent is attached.

Failsafe runs the `*IT` classes (integration tests, which start a whole application) in the `integration-test` phase and fails the build in `verify`:

```xml
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <argLine>@{argLine} -XX:+EnableDynamicAgentLoading -Xshare:off</argLine>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

The `<executions>` block binds two goals to the lifecycle: `integration-test` runs the tests and `verify` checks the result. Surefire needs no such block because Spring Boot's parent already binds it.

The JaCoCo, Spotless and Checkstyle plugin blocks are explained in `jacoco.md`, `spotless-google-java-format.md` and `checkstyle.md`.

### The complete build files

Every `pom.xml` in the repository follows, quoted whole. Under each, the "Reading it, element by element" list walks through the file from top to bottom. An XML element is a pair of tags such as `<version>0.1.0</version>`: the name between angle brackets, the content, and the same name with a slash to close. Elements nest inside each other, and the list shows the nesting with indentation.

### `pom.xml`

The whole file, as it is in the repository:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.2</version>
    <relativePath/>
  </parent>

  <groupId>dev.plainround</groupId>
  <artifactId>spring-resilient-client</artifactId>
  <version>0.1.0</version>
  <packaging>pom</packaging>
  <name>spring-resilient-client</name>
  <description>A price service that stays fast and honest while its provider is slow, failing or silent.</description>

  <modules>
    <module>provider</module>
    <module>loadgen</module>
    <module>price-service</module>
  </modules>

  <properties>
    <java.version>25</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <archunit.version>1.5.1</archunit.version>
    <spotless.version>3.10.4</spotless.version>
    <google-java-format.version>1.30.0</google-java-format.version>
    <checkstyle-plugin.version>3.6.0</checkstyle-plugin.version>
    <checkstyle.version>14.3.0</checkstyle.version>
    <jacoco.version>0.8.16</jacoco.version>
    <jacoco.bundle.minimum>0.85</jacoco.bundle.minimum>
  </properties>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-jar-plugin</artifactId>
          <configuration>
            <archive>
              <manifest>
                <mainClass>${start-class}</mainClass>
              </manifest>
            </archive>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>

    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <parameters>true</parameters>
          <showWarnings>true</showWarnings>
          <compilerArgs>
            <arg>-Xlint:all,-processing</arg>
            <arg>-Werror</arg>
          </compilerArgs>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading -Xshare:off</argLine>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <argLine>@{argLine} -XX:+EnableDynamicAgentLoading -Xshare:off</argLine>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>

      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>${jacoco.version}</version>
        <executions>
          <execution>
            <id>prepare-agent</id>
            <goals>
              <goal>prepare-agent</goal>
            </goals>
          </execution>
          <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals>
              <goal>report</goal>
            </goals>
          </execution>
          <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals>
              <goal>check</goal>
            </goals>
            <configuration>
              <rules>
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

      <plugin>
        <groupId>com.diffplug.spotless</groupId>
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

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
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
    </plugins>
  </build>
</project>
```

Reading it, element by element (each line is one XML element, indented under the element that contains it):

- `<project>`: the root element of a Maven build description (a POM, "project object model").
  - `<modelVersion>` `4.0.0`: the version of the POM format itself; Maven 3 and 4 files use 4.0.0.
  - `<parent>`: a POM this one inherits from: its versions, plugin settings and properties apply here too.
    - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
    - `<artifactId>` `spring-boot-starter-parent`: the name part of the coordinates, like a first name.
    - `<version>` `4.1.2`: the version part of the coordinates.
    - `<relativePath>`: where to look for the parent on disk; empty means "do not look, download it from the repository".
  - `<groupId>` `dev.plainround`: the organization or namespace part of the coordinates, like a surname.
  - `<artifactId>` `spring-resilient-client`: the name part of the coordinates, like a first name.
  - `<version>` `0.1.0`: the version part of the coordinates.
  - `<packaging>` `pom`: what the build produces: `pom` means a parent that only lists modules and settings, with no code of its own; the default is `jar`.
  - `<name>` `spring-resilient-client`: a human-readable name.
  - `<description>` `A price service that stays fast and honest while its provider is slow, failing or silent.`: a one-sentence description.
  - `<modules>`: the folders that are built as part of this project, in this order unless dependencies say otherwise.
    - `<module>` `provider`: one sub-project folder.
    - `<module>` `loadgen`: one sub-project folder.
    - `<module>` `price-service`: one sub-project folder.
  - `<properties>`: named values that the rest of the file can use as `${name}`.
    - `<java.version>` = `25`: the Java release the code is compiled for; the Spring Boot parent reads it.
    - `<project.build.sourceEncoding>` = `UTF-8`: how source files are stored as bytes (UTF-8).
    - `<archunit.version>` = `1.5.1`: the ArchUnit version, used where the dependency is declared.
    - `<spotless.version>` = `3.10.4`: the Spotless plugin version.
    - `<google-java-format.version>` = `1.30.0`: the formatter version Spotless uses.
    - `<checkstyle-plugin.version>` = `3.6.0`: the version of the Maven plugin that runs Checkstyle.
    - `<checkstyle.version>` = `14.3.0`: the version of the Checkstyle engine itself.
    - `<jacoco.version>` = `0.8.16`: the JaCoCo version.
    - `<jacoco.bundle.minimum>` = `0.85`: the minimum covered share of lines for the whole module.
  - `<build>`: how the project is built.
    - `<pluginManagement>`: settings for plugins that apply only if a module uses the plugin; nothing runs from here by itself.
      - `<plugins>`: the build plugins that run.
        - `<plugin>`: one plugin: a program Maven runs during the build.
          - `<groupId>` `org.apache.maven.plugins`: the organization or namespace part of the coordinates, like a surname.
          - `<artifactId>` `maven-jar-plugin`: the name part of the coordinates, like a first name.
          - `<configuration>`: the settings of the plugin or execution around it.
            - `<archive>`: settings for the jar file that gets built.
              - `<manifest>`: the `META-INF/MANIFEST.MF` file inside the jar.
                - `<mainClass>` `${start-class}`: the class whose `main` method starts when someone runs `java -jar`.
    - `<plugins>`: the build plugins that run.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.apache.maven.plugins`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `maven-compiler-plugin`: the name part of the coordinates, like a first name.
        - `<configuration>`: the settings of the plugin or execution around it.
          - `<parameters>` `true`: keep method parameter names in the compiled classes, which Spring needs to match request parameters to Java parameters.
          - `<showWarnings>` `true`: print every compiler warning.
          - `<compilerArgs>`: extra flags passed to the Java compiler.
            - `<arg>` `-Xlint:all,-processing`: one flag.
            - `<arg>` `-Werror`: one flag.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.apache.maven.plugins`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `maven-surefire-plugin`: the name part of the coordinates, like a first name.
        - `<configuration>`: the settings of the plugin or execution around it.
          - `<argLine>` `@{argLine} -XX:+EnableDynamicAgentLoading -Xshare:off`: extra flags for the test JVM; `@{argLine}` keeps the JaCoCo agent flag that JaCoCo sets earlier.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.apache.maven.plugins`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `maven-failsafe-plugin`: the name part of the coordinates, like a first name.
        - `<configuration>`: the settings of the plugin or execution around it.
          - `<argLine>` `@{argLine} -XX:+EnableDynamicAgentLoading -Xshare:off`: extra flags for the test JVM; `@{argLine}` keeps the JaCoCo agent flag that JaCoCo sets earlier.
        - `<executions>`: when the plugin runs.
          - `<execution>`: one run of the plugin, bound to a phase.
            - `<goals>`: the plugin functions to run.
              - `<goal>` `integration-test`: one plugin function.
              - `<goal>` `verify`: one plugin function.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.jacoco`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `jacoco-maven-plugin`: the name part of the coordinates, like a first name.
        - `<version>` `${jacoco.version}`: the version part of the coordinates.
        - `<executions>`: when the plugin runs.
          - `<execution>`: one run of the plugin, bound to a phase.
            - `<id>` `prepare-agent`: a name for this execution, so other files can refer to it.
            - `<goals>`: the plugin functions to run.
              - `<goal>` `prepare-agent`: one plugin function.
          - `<execution>`: one run of the plugin, bound to a phase.
            - `<id>` `report`: a name for this execution, so other files can refer to it.
            - `<phase>` `verify`: the stage of the Maven lifecycle at which this execution runs.
            - `<goals>`: the plugin functions to run.
              - `<goal>` `report`: one plugin function.
          - `<execution>`: one run of the plugin, bound to a phase.
            - `<id>` `check`: a name for this execution, so other files can refer to it.
            - `<phase>` `verify`: the stage of the Maven lifecycle at which this execution runs.
            - `<goals>`: the plugin functions to run.
              - `<goal>` `check`: one plugin function.
            - `<configuration>`: the settings of the plugin or execution around it.
              - `<rules>`: the checks that decide whether the build passes.
                - `<rule>`: one check.
                  - `<element>` `BUNDLE`: what the check measures: `BUNDLE` is the whole module, `PACKAGE` one package.
                  - `<limits>`: the thresholds for the check.
                    - `<limit>`: one threshold.
                      - `<counter>` `LINE`: what is counted: one of `INSTRUCTION`, `LINE`, `BRANCH`, `COMPLEXITY`, `METHOD` or `CLASS`.
                      - `<value>` `COVEREDRATIO`: how it is expressed: `COVEREDRATIO` is the covered share between 0 and 1.
                      - `<minimum>` `${jacoco.bundle.minimum}`: the lowest allowed value; below it the build fails.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `com.diffplug.spotless`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `spotless-maven-plugin`: the name part of the coordinates, like a first name.
        - `<version>` `${spotless.version}`: the version part of the coordinates.
        - `<configuration>`: the settings of the plugin or execution around it.
          - `<java>`: settings for formatting Java files.
            - `<googleJavaFormat>`: use the google-java-format formatter.
              - `<version>` `${google-java-format.version}`: the version part of the coordinates.
              - `<style>` `AOSP`: the formatter style; `AOSP` indents by four spaces, as Android does.
              - `<reflowLongStrings>` `true`: break long string literals over several lines to stay within the line limit.
            - `<removeUnusedImports>`: delete `import` lines nothing uses.
            - `<importOrder>`: sort the imports (the default order).
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.apache.maven.plugins`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `maven-checkstyle-plugin`: the name part of the coordinates, like a first name.
        - `<version>` `${checkstyle-plugin.version}`: the version part of the coordinates.
        - `<dependencies>`: the libraries this module needs.
          - `<dependency>`: one library, identified by its coordinates.
            - `<groupId>` `com.puppycrawl.tools`: the organization or namespace part of the coordinates, like a surname.
            - `<artifactId>` `checkstyle`: the name part of the coordinates, like a first name.
            - `<version>` `${checkstyle.version}`: the version part of the coordinates.
        - `<configuration>`: the settings of the plugin or execution around it.
          - `<configLocation>` `${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml`: the Checkstyle rule file.
          - `<suppressionsLocation>` `${maven.multiModuleProjectDirectory}/config/checkstyle/suppressions.xml`: the file that lists where rules are switched off.
          - `<includeTestSourceDirectory>` `true`: check the test code as well as the main code.
          - `<consoleOutput>` `true`: print the violations in the build output.
          - `<failOnViolation>` `true`: fail the build when a violation is found.
          - `<violationSeverity>` `warning`: the lowest severity that counts as a violation.


### `provider/pom.xml`

The whole file, as it is in the repository:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>dev.plainround</groupId>
    <artifactId>spring-resilient-client</artifactId>
    <version>0.1.0</version>
  </parent>

  <artifactId>provider</artifactId>
  <name>provider</name>
  <description>The fake quote provider: a plain JDK HttpServer you can switch between normal, slow, 503 and hang.</description>

  <properties>
    <start-class>dev.plainround.provider.ProviderMain</start-class>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

Reading it, element by element (each line is one XML element, indented under the element that contains it):

- `<project>`: the root element of a Maven build description (a POM, "project object model").
  - `<modelVersion>` `4.0.0`: the version of the POM format itself; Maven 3 and 4 files use 4.0.0.
  - `<parent>`: a POM this one inherits from: its versions, plugin settings and properties apply here too.
    - `<groupId>` `dev.plainround`: the organization or namespace part of the coordinates, like a surname.
    - `<artifactId>` `spring-resilient-client`: the name part of the coordinates, like a first name.
    - `<version>` `0.1.0`: the version part of the coordinates.
  - `<artifactId>` `provider`: the name part of the coordinates, like a first name.
  - `<name>` `provider`: a human-readable name.
  - `<description>` `The fake quote provider: a plain JDK HttpServer you can switch between normal, slow, 503 and hang.`: a one-sentence description.
  - `<properties>`: named values that the rest of the file can use as `${name}`.
    - `<start-class>` = `dev.plainround.provider.ProviderMain`: the class with `main`, read by the Spring Boot plugin and the jar plugin.
  - `<dependencies>`: the libraries this module needs.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.junit.jupiter`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `junit-jupiter`: the name part of the coordinates, like a first name.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.assertj`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `assertj-core`: the name part of the coordinates, like a first name.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
  - `<build>`: how the project is built.
    - `<plugins>`: the build plugins that run.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.apache.maven.plugins`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `maven-jar-plugin`: the name part of the coordinates, like a first name.


### `loadgen/pom.xml`

The whole file, as it is in the repository:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>dev.plainround</groupId>
    <artifactId>spring-resilient-client</artifactId>
    <version>0.1.0</version>
  </parent>

  <artifactId>loadgen</artifactId>
  <name>loadgen</name>
  <description>A paced HTTP load generator: N requests a second for D seconds, with p50, p99 and error rate.</description>

  <properties>
    <start-class>dev.plainround.loadgen.LoadMain</start-class>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

Reading it, element by element (each line is one XML element, indented under the element that contains it):

- `<project>`: the root element of a Maven build description (a POM, "project object model").
  - `<modelVersion>` `4.0.0`: the version of the POM format itself; Maven 3 and 4 files use 4.0.0.
  - `<parent>`: a POM this one inherits from: its versions, plugin settings and properties apply here too.
    - `<groupId>` `dev.plainround`: the organization or namespace part of the coordinates, like a surname.
    - `<artifactId>` `spring-resilient-client`: the name part of the coordinates, like a first name.
    - `<version>` `0.1.0`: the version part of the coordinates.
  - `<artifactId>` `loadgen`: the name part of the coordinates, like a first name.
  - `<name>` `loadgen`: a human-readable name.
  - `<description>` `A paced HTTP load generator: N requests a second for D seconds, with p50, p99 and error rate.`: a one-sentence description.
  - `<properties>`: named values that the rest of the file can use as `${name}`.
    - `<start-class>` = `dev.plainround.loadgen.LoadMain`: the class with `main`, read by the Spring Boot plugin and the jar plugin.
  - `<dependencies>`: the libraries this module needs.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.junit.jupiter`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `junit-jupiter`: the name part of the coordinates, like a first name.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.assertj`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `assertj-core`: the name part of the coordinates, like a first name.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
  - `<build>`: how the project is built.
    - `<plugins>`: the build plugins that run.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.apache.maven.plugins`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `maven-jar-plugin`: the name part of the coordinates, like a first name.


### `price-service/pom.xml`

The whole file, as it is in the repository:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>dev.plainround</groupId>
    <artifactId>spring-resilient-client</artifactId>
    <version>0.1.0</version>
  </parent>

  <artifactId>price-service</artifactId>
  <name>price-service</name>
  <description>A price API that calls a provider through RestClient and stays fast and honest when the provider misbehaves.</description>

  <properties>
    <jacoco.domain.minimum>0.95</jacoco.domain.minimum>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-restclient</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-opentelemetry</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <dependency>
      <groupId>dev.plainround</groupId>
      <artifactId>provider</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <version>${archunit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>
              <groupId>dev.plainround</groupId>
              <artifactId>provider</artifactId>
            </exclude>
          </excludes>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>repackage</goal>
            </goals>
          </execution>
        </executions>
      </plugin>

      <plugin>
        <groupId>org.jacoco</groupId>
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
    </plugins>
  </build>
</project>
```

Reading it, element by element (each line is one XML element, indented under the element that contains it):

- `<project>`: the root element of a Maven build description (a POM, "project object model").
  - `<modelVersion>` `4.0.0`: the version of the POM format itself; Maven 3 and 4 files use 4.0.0.
  - `<parent>`: a POM this one inherits from: its versions, plugin settings and properties apply here too.
    - `<groupId>` `dev.plainround`: the organization or namespace part of the coordinates, like a surname.
    - `<artifactId>` `spring-resilient-client`: the name part of the coordinates, like a first name.
    - `<version>` `0.1.0`: the version part of the coordinates.
  - `<artifactId>` `price-service`: the name part of the coordinates, like a first name.
  - `<name>` `price-service`: a human-readable name.
  - `<description>` `A price API that calls a provider through RestClient and stays fast and honest when the provider misbehaves.`: a one-sentence description.
  - `<properties>`: named values that the rest of the file can use as `${name}`.
    - `<jacoco.domain.minimum>` = `0.95`: the minimum covered share of lines for the domain package.
  - `<dependencies>`: the libraries this module needs.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `spring-boot-starter-webmvc`: the name part of the coordinates, like a first name.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `spring-boot-starter-restclient`: the name part of the coordinates, like a first name.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `spring-boot-starter-validation`: the name part of the coordinates, like a first name.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `spring-boot-starter-actuator`: the name part of the coordinates, like a first name.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `spring-boot-starter-opentelemetry`: the name part of the coordinates, like a first name.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `io.micrometer`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `micrometer-registry-prometheus`: the name part of the coordinates, like a first name.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `dev.plainround`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `provider`: the name part of the coordinates, like a first name.
      - `<version>` `${project.version}`: the version part of the coordinates.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `spring-boot-starter-test`: the name part of the coordinates, like a first name.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `spring-boot-starter-webmvc-test`: the name part of the coordinates, like a first name.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
    - `<dependency>`: one library, identified by its coordinates.
      - `<groupId>` `com.tngtech.archunit`: the organization or namespace part of the coordinates, like a surname.
      - `<artifactId>` `archunit-junit5`: the name part of the coordinates, like a first name.
      - `<version>` `${archunit.version}`: the version part of the coordinates.
      - `<scope>` `test`: when the library is on the classpath: `test` means only while running tests, `runtime` only when the program runs, none means everywhere.
  - `<build>`: how the project is built.
    - `<plugins>`: the build plugins that run.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.springframework.boot`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `spring-boot-maven-plugin`: the name part of the coordinates, like a first name.
        - `<configuration>`: the settings of the plugin or execution around it.
          - `<excludes>`: these items are left out.
            - `<exclude>`: one item to leave out.
              - `<groupId>` `dev.plainround`: the organization or namespace part of the coordinates, like a surname.
              - `<artifactId>` `provider`: the name part of the coordinates, like a first name.
        - `<executions>`: when the plugin runs.
          - `<execution>`: one run of the plugin, bound to a phase.
            - `<goals>`: the plugin functions to run.
              - `<goal>` `repackage`: one plugin function.
      - `<plugin>`: one plugin: a program Maven runs during the build.
        - `<groupId>` `org.jacoco`: the organization or namespace part of the coordinates, like a surname.
        - `<artifactId>` `jacoco-maven-plugin`: the name part of the coordinates, like a first name.
        - `<executions>`: when the plugin runs.
          - `<execution>`: one run of the plugin, bound to a phase.
            - `<id>` `check`: a name for this execution, so other files can refer to it.
            - `<configuration>`: the settings of the plugin or execution around it.
              - `<rules>`: the checks that decide whether the build passes.
                - `<rule>`: one check.
                  - `<element>` `PACKAGE`: what the check measures: `BUNDLE` is the whole module, `PACKAGE` one package.
                  - `<includes>`: only these items are used.
                    - `<include>` `*.domain`: one item to use.
                  - `<limits>`: the thresholds for the check.
                    - `<limit>`: one threshold.
                      - `<counter>` `LINE`: what is counted: one of `INSTRUCTION`, `LINE`, `BRANCH`, `COMPLEXITY`, `METHOD` or `CLASS`.
                      - `<value>` `COVEREDRATIO`: how it is expressed: `COVEREDRATIO` is the covered share between 0 and 1.
                      - `<minimum>` `${jacoco.domain.minimum}`: the lowest allowed value; below it the build fails.
                - `<rule>`: one check.
                  - `<element>` `BUNDLE`: what the check measures: `BUNDLE` is the whole module, `PACKAGE` one package.
                  - `<limits>`: the thresholds for the check.
                    - `<limit>`: one threshold.
                      - `<counter>` `LINE`: what is counted: one of `INSTRUCTION`, `LINE`, `BRANCH`, `COMPLEXITY`, `METHOD` or `CLASS`.
                      - `<value>` `COVEREDRATIO`: how it is expressed: `COVEREDRATIO` is the covered share between 0 and 1.
                      - `<minimum>` `${jacoco.bundle.minimum}`: the lowest allowed value; below it the build fails.


## Try it yourself

1. `./mvnw -v` prints the Maven and JDK versions the wrapper uses. The first run downloads Maven.
2. `./mvnw -q compile` compiles and prints nothing when it works.
3. `./mvnw test` runs the unit tests. Look for `Tests run: N, Failures: 0, Errors: 0`.
4. `./mvnw verify` runs everything, including `*IT` tests and the coverage check. Look for `BUILD SUCCESS`.
5. `./mvnw dependency:tree` prints every library the project pulls in, indented by who needs whom.

## Mistakes people make

- Running `mvn` instead of `./mvnw`: you may get a different Maven version.
- Editing a file under `target/`: it is overwritten by the next build.
- Adding a `<version>` to a dependency the parent already manages: it overrides a tested combination.
- Reading `BUILD FAILURE` and stopping at the first red line: the cause is usually lower, after `Tests run:` or `[ERROR]`.
- Committing `target/`: this repository's `.gitignore` excludes it.

## Check yourself

1. What is the difference between `test` and `verify`? *`verify` also runs the integration tests and the coverage check.*
2. Why is there a wrapper? *Everyone builds with the same Maven without installing it.*
3. What does `<scope>test</scope>` mean? *The library is only on the classpath of the tests.*
4. Why do the Spring starters have no version? *The parent POM manages versions.*
5. What does `-Werror` do? *A compiler warning fails the build.*

## Further reading

- Maven documentation, "Introduction to the Build Lifecycle": https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- Maven Wrapper: https://maven.apache.org/tools/wrapper/
- Spring Boot 4.1.2 Maven plugin and parent: https://docs.spring.io/spring-boot/4.1.2/build-tool-plugin/maven.html
