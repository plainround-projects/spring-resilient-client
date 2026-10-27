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

1. You run `./mvnw verify`. The script reads `.mvn/wrapper/maven-wrapper.properties`, downloads that Maven version into `~/.m2/wrapper` if it is not there yet, and runs it.
2. Maven reads `pom.xml`. If the file has a `<parent>`, Maven first reads the parent's POM and inherits its settings.
3. Maven works through a **lifecycle**: a fixed list of **phases**. The ones that matter here, in order: `validate`, `compile`, `test`, `package`, `verify`. Asking for a phase runs every phase before it.
4. Plugins attach to phases. `maven-compiler-plugin` runs in `compile`, `maven-surefire-plugin` runs the unit tests in `test`, `maven-failsafe-plugin` runs the integration tests after `package`, and `jacoco-maven-plugin` checks coverage in `verify`.
5. Output goes to the `target/` folder. It is rebuilt from the source, so it is never committed.
6. A **dependency** has coordinates: a `groupId`, an `artifactId` and a `version`. A **scope** says where it is needed: `compile` (default), `runtime`, or `test` (only for tests).

## Walkthrough of this repository's use

**The wrapper.**

```properties
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

`distributionType=only-script` means the wrapper does not carry a `.jar` file; the script does the download itself. `distributionUrl` is the exact Maven that gets downloaded (3.9.11), so every machine and CI use the same one.

**The JVM options.**

```properties
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
```

The Spotless plugin (see `spotless-google-java-format.md`) uses internals of the Java compiler. Newer JDKs close those internals unless you open them. `--add-exports=module/package=ALL-UNNAMED` opens one internal package to all code. Maven reads this file on every start and passes the lines to the JVM.

**The parent.**

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.2</version>
  <relativePath/>
</parent>
```

The root POM inherits from Spring Boot's parent, and the three module POMs inherit from the root (`price-service/pom.xml` has `<parent>` with `spring-resilient-client`). So a plugin version is written once, in the root, and applies everywhere. The root's `<packaging>pom</packaging>` and its `<modules>` list say "this project builds nothing itself; it builds these three":

```xml
<modules>
  <module>provider</module>
  <module>loadgen</module>
  <module>price-service</module>
</modules>
```

Maven works out the build order from the dependencies between modules: `provider` first, because `price-service` uses it in its tests.

**Properties.** `<properties>` holds named values reused below.

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

`java.version` is read by Spring Boot's parent, which passes it to the compiler as `maven.compiler.release`. `--release 25` is stricter than `-source`/`-target`: the code can only call library methods that exist in Java 25. The other lines pin plugin and tool versions so that nothing changes by surprise.

**Dependencies.**

```xml
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
  </dependency>
```

Each `<dependency>` is a library. Spring Boot **starters** (`spring-boot-starter-...`) are bundles: one line pulls in the library and its usual companions. No `<version>` because the parent decides. Test libraries use `<scope>test</scope>`, so they are not in the final application.

**The compiler plugin.**

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

`<parameters>true</parameters>` keeps the names of method parameters in the bytecode, which Spring uses to report errors by parameter name. `-Xlint:all,-processing` turns on every compiler warning except the annotation-processing ones, and `-Werror` makes any warning fail the build.

**Surefire and failsafe.** Surefire runs classes named `*Test` in the `test` phase. Failsafe runs `*IT` (integration tests, which start a whole application) in `integration-test` and fails the build in `verify`.

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

`@{argLine}` is a placeholder that JaCoCo fills with the option that attaches its coverage agent; without it, coverage of the integration tests would be lost. `-XX:+EnableDynamicAgentLoading` silences a JDK warning about the Mockito agent. `-Xshare:off` avoids a JDK warning when an agent is attached.

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
