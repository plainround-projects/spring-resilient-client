# Technology notes for spring-resilient-client

This folder teaches every technology the repository uses, from zero, through the repository's own files. Each note says what the technology is, why this repository uses it, where it appears, how it works, and then walks through the repository's own lines. You need no earlier knowledge.

## The files and the order to read them

Read from the top: the language first, then the build tool, then the frameworks, then the runtime pieces, then the tests, then the tools around the code.

| # | Note | Role in this repository |
| ---: | --- | --- |
| 1 | [Java 25](java.md) | the language every class, test and build plugin is written in |
| 2 | [Maven and the Maven wrapper](maven.md) | builds, tests and packages the code; `./mvnw` is the only build command you need |
| 3 | [XML](xml.md) | the file format of `pom.xml` and of the Checkstyle rules |
| 4 | [Properties files (application.properties)](properties-files.md) | the one file of settings that Spring Boot reads at startup |
| 5 | [Spring Framework (beans, injection, proxies)](spring-framework.md) | builds the objects, hands each one what it needs, and wraps some of them with extra behavior |
| 6 | [Spring Boot 4.1.2](spring-boot.md) | starts the application, configures it from the libraries on the classpath, and provides the starters |
| 7 | [Spring Web MVC (controllers, filters, error advice)](spring-web-mvc.md) | turns HTTP requests into method calls and results into HTTP answers |
| 8 | [Bean Validation (Jakarta Validation)](bean-validation.md) | checks input at the boundary with annotations such as `@NotBlank` and `@Min` |
| 9 | [Problem Details (RFC 9457, application/problem+json)](problem-details.md) | the one JSON shape every error answer in this service uses |
| 10 | [JSON and Jackson](json-jackson.md) | the text format of every request and answer, and the library that converts it to and from Java records |
| 11 | [Spring Boot Actuator (health, probes, metrics endpoints)](spring-boot-actuator.md) | the endpoints that say whether the service is alive and ready, and expose its metrics |
| 12 | [Logging (SLF4J, Logback, structured JSON)](structured-logging.md) | writes one JSON object per log line, with a request id and trace ids |
| 13 | [JUnit (Jupiter)](junit.md) | runs every test method and reports which passed |
| 14 | [RestClient (calling another service over HTTP)](rest-client.md) | makes the call from the price service to the provider, with a connect and a read timeout |
| 15 | [AssertJ](assertj.md) | the readable assertions (`assertThat(x).isEqualTo(y)`) used in every test |
| 16 | [Spring Framework resilience annotations (@Retryable, @ConcurrencyLimit)](spring-resilience.md) | bounded retries and a limit on concurrent provider calls, as annotations |
| 17 | [Mockito](mockito.md) | creates stand-in objects ("mocks") for the collaborators a test does not want to run |
| 18 | [Resilience patterns (timeouts, bounded retries, load shedding, stale cache)](resilience-patterns.md) | the ideas behind every decision in the price service, in one place |
| 19 | [Spring Boot Test (test slices, MockMvc, full-context tests)](spring-boot-test.md) | starts as much of the application as a test needs, from the web layer only to the whole service |
| 20 | [Virtual threads](virtual-threads.md) | lets the price service wait on many slow provider calls without using up threads |
| 21 | [ArchUnit](archunit.md) | turns the dependency rule between layers into a test that fails the build |
| 22 | [Micrometer metrics and OpenTelemetry traces](micrometer-opentelemetry.md) | counts and times what the price service does, and records the path of each request |
| 23 | [Grafana, Prometheus and Tempo (the grafana/otel-lgtm image)](grafana-stack.md) | receives the service's metrics and traces and shows them on a dashboard |
| 24 | [JaCoCo (code coverage)](jacoco.md) | measures which lines the tests ran and fails the build below a limit |
| 25 | [The JDK's HTTP server and HTTP client (the provider and the load generator)](jdk-http.md) | the plain-Java fake provider and the load generator, built on classes that ship with Java |
| 26 | [Spotless and google-java-format](spotless-google-java-format.md) | formats every Java file the same way, and fails the build if a file is not formatted |
| 27 | [Checkstyle](checkstyle.md) | enforces the written rules a formatter cannot: names, imports, public Javadoc, no leftovers |
| 28 | [EditorConfig](editorconfig.md) | tells every editor the same indentation, line ending and charset for each file type |
| 29 | [Git and .gitignore](git-and-gitignore.md) | keeps the history of the repository and decides which files are never recorded |
| 30 | [Dockerfile (container images)](dockerfile.md) | builds the image that runs the service: a multi-stage build ending in a small, non-root runtime |
| 31 | [Docker Compose](docker-compose.md) | starts the service, the fake provider and the Grafana stack together with one command |
| 32 | [YAML](yaml.md) | the file format of `docker-compose.yml` and the GitHub Actions workflow, and of the Grafana provisioning file |
| 33 | [Make and the Makefile](make.md) | gives every task a short name: `make up`, `make run`, `make test`, `make lint`, `make down` |
| 34 | [Bash (the scripts)](bash.md) | the scripts under `scripts/` that run the lesson's scenario, run the benchmark and capture traces |
| 35 | [curl](curl.md) | sends HTTP requests from the command line; the scripts and README examples use it |
| 36 | [jq](jq.md) | reads and reshapes JSON on the command line |
| 37 | [GitHub Actions](github-actions.md) | runs lint, tests, the dependency audit and the image build on every push |
| 38 | [OSV-Scanner (dependency audit)](osv-scanner.md) | checks every library the project uses against a database of known vulnerabilities |

## If you have ten minutes

Read these three, in this order:

1. [RestClient (calling another service over HTTP)](rest-client.md): makes the call from the price service to the provider, with a connect and a read timeout
1. [Spring Framework resilience annotations (@Retryable, @ConcurrencyLimit)](spring-resilience.md): bounded retries and a limit on concurrent provider calls, as annotations
1. [Resilience patterns (timeouts, bounded retries, load shedding, stale cache)](resilience-patterns.md): the ideas behind every decision in the price service, in one place
