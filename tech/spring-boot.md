# Spring Boot 4.1.2

## What it is

Spring Boot is a layer on top of Spring Framework that removes setup work. It gives you three things: **starters** (one dependency that brings a whole feature), **auto-configuration** (it looks at which libraries are on the classpath and creates the beans you would otherwise write by hand), and a **runnable jar** (one file with an embedded web server inside, started with `java -jar`).

## Why this repository uses it

The lesson says to start from a Spring Boot project with a few starters and "nothing else". Boot supplies the web server, JSON handling, validation, health checks and logging with almost no code, so the project's own code is only the lesson.

## Where it appears

| Path | What it does |
| --- | --- |
| `PriceServiceApplication.java` | the entry point, marked `@SpringBootApplication` |
| `pom.xml` | the Boot parent and the starters |
| `application.properties` | the settings Boot reads |
| `interfaces/web/*Properties.java` or `infrastructure/*Properties.java` | records bound to settings with `@ConfigurationProperties` |
| `Dockerfile` | uses Boot's `-Djarmode=tools extract --layers` to split the jar |

## How it works underneath

A **jar** is a zip file of compiled Java classes; a "runnable" jar also contains a start class so `java -jar file.jar` works. An **embedded server** means the web server (Tomcat) is a library inside your program, not a separate installation. A **port** is the number a program listens on for network connections. The **JVM** runs your bytecode. A **process** is a running program; **SIGTERM** is the polite "please stop" signal an operating system or Docker sends.

1. `main` calls `SpringApplication.run(...)`. Boot builds the application context, starts the embedded **Tomcat** web server on port 8080 (or `server.port`), and keeps the process alive.
2. `@SpringBootApplication` is three annotations in one: it marks a configuration class, turns on **auto-configuration**, and turns on component scanning of the package and below.
3. Auto-configuration is a list of configuration classes shipped inside Boot's jars. Each is guarded by **conditions** such as "only if this class exists" or "only if no bean of this type exists yet". When you add the web starter, the Tomcat and MVC configuration classes switch on; if you define your own bean of the same type, Boot's backs off.
4. `./mvnw spring-boot:run` runs the application from the source tree; `mvn package` makes the runnable jar (the plugin "repackages" the ordinary jar with its libraries inside).
5. When the process receives SIGTERM (the polite stop signal), Boot runs a **graceful shutdown** if asked to: it stops accepting new requests, waits for running ones, then exits.

## Walkthrough of this repository's use

The entry point:

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class PriceServiceApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments, passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(PriceServiceApplication.class, args);
    }
}
```

`@SpringBootApplication` does the three things above. `@ConfigurationPropertiesScan` additionally finds every class marked `@ConfigurationProperties` and registers it, so no `@EnableConfigurationProperties` list is needed. `SpringApplication.run(PriceServiceApplication.class, args)` starts everything; `args` lets you pass `--server.port=...` on the command line.

**Starters** (from the POM):

```xml
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
```

`webmvc` brings Tomcat, Spring MVC and Jackson; `validation` brings Bean Validation; `actuator` brings the health and metrics endpoints. `restclient` brings `RestClient`; `opentelemetry` brings the tracing and OTLP export.

**Settings as typed objects.**

```java
@Validated
@ConfigurationProperties("price.provider")
public record ProviderProperties(
        @NotBlank String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout) {

    /**
     * Checks that both timeouts are positive.
     *
     * @return true when neither timeout is zero or negative
     */
    @AssertTrue(message = "connect-timeout and read-timeout must be positive")
    public boolean isTimeoutsPositive() {
        return connectTimeout.isPositive() && readTimeout.isPositive();
    }
}
```

`@ConfigurationProperties("...")` on a **record** binds the settings with that prefix to the record's components. `@DefaultValue` gives a default. `@Validated` runs Bean Validation on the result at startup, so an impossible value (a timeout of zero, a default page size above the maximum) stops the application with a message instead of causing a bug later. The `@AssertTrue` method is a rule across two fields.

**Graceful shutdown and probes** are two lines in `application.properties`: `server.shutdown=graceful` and `management.endpoint.health.probes.enabled=true`.

## Try it yourself

1. `./mvnw spring-boot:run -pl price-service` starts the application from source.
2. `./mvnw -DskipTests package && java -jar price-service/target/price-service-0.1.0.jar` runs the packaged jar. `unzip -l` on that file shows `BOOT-INF/lib/` with the libraries inside.
3. Start with `--debug` (`java -jar ... --debug`) and search the log for `CONDITIONS EVALUATION REPORT`: it lists every auto-configuration that matched and why.
4. Stop the process with Ctrl+C and read the last log lines: `Commencing graceful shutdown` then `Graceful shutdown complete`.

## Mistakes people make

- Putting the main class outside the package that holds the other classes: component scanning then misses them.
- Adding a library and expecting no effect: auto-configuration may switch on a feature (a datasource, security).
- Editing `application.properties` and not restarting.
- Using `@Autowired` fields instead of the constructor (this repository forbids it with a test).

## Check yourself

1. What is a starter? *One dependency that brings a feature's libraries.*
2. What makes Boot "back off" from a bean? *A bean of that type that you define.*
3. What does `@SpringBootApplication` combine? *Configuration, auto-configuration, component scan.*
4. What does graceful shutdown wait for? *Requests already running.*
5. Why validate a `@ConfigurationProperties` record? *A bad setting fails at startup.*

## Further reading

- Spring Boot 4.1.2 reference: https://docs.spring.io/spring-boot/4.1.2/reference/index.html
- Auto-configuration: https://docs.spring.io/spring-boot/4.1.2/reference/using/auto-configuration.html
- Graceful shutdown: https://docs.spring.io/spring-boot/4.1.2/reference/web/graceful-shutdown.html
