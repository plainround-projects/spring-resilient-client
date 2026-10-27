# Technology notes for spring-resilient-client

This folder teaches every technology the repository uses, from zero, through the repository's own files. Each note says what the technology is, why this repository uses it, where it appears, how it works, and then walks through the repository's own lines. You need no earlier knowledge.

## The files and the order to read them

Read from the top: the language first, then the build tool, then the frameworks, then the runtime pieces, then the tests, then the tools around the code.

| # | Note | Role in this repository |
| ---: | --- | --- |
| 1 | [Java 25](java.md) | the language every class, test and build plugin is written in |
| 2 | [The application code, class by class](java-main-code.md) | every class of the running application, quoted whole and explained piece by piece |
| 3 | [The test code, class by class](java-test-code.md) | every automated test, quoted whole and explained piece by piece |
| 4 | [Maven and the Maven wrapper](maven.md) | builds, tests and packages the code; `./mvnw` is the only build command you need |
| 5 | [The Maven wrapper script (mvnw)](maven-wrapper.md) | the shell script that downloads the right Maven and runs it, so nobody has to install Maven first |
| 6 | [The Windows wrapper script (mvnw.cmd)](windows-batch.md) | the Windows twin of mvnw: a file that is both a batch script and a PowerShell script |
| 7 | [XML](xml.md) | the file format of `pom.xml` and of the Checkstyle rules |
| 8 | [Properties files (application.properties)](properties-files.md) | the one file of settings that Spring Boot reads at startup |
| 9 | [Spring Framework (beans, injection, proxies)](spring-framework.md) | builds the objects, hands each one what it needs, and wraps some of them with extra behavior |
| 10 | [Spring Boot 4.1.2](spring-boot.md) | starts the application, configures it from the libraries on the classpath, and provides the starters |
| 11 | [Spring Web MVC (controllers, filters, error advice)](spring-web-mvc.md) | turns HTTP requests into method calls and results into HTTP answers |
| 12 | [Bean Validation (Jakarta Validation)](bean-validation.md) | checks input at the boundary with annotations such as `@NotBlank` and `@Min` |
| 13 | [Problem Details (RFC 9457, application/problem+json)](problem-details.md) | the one JSON shape every error answer in this service uses |
| 14 | [JSON and Jackson](json-jackson.md) | the text format of every request and answer, and the library that converts it to and from Java records |
| 15 | [Spring Boot Actuator (health, probes, metrics endpoints)](spring-boot-actuator.md) | the endpoints that say whether the service is alive and ready, and expose its metrics |
| 16 | [Logging (SLF4J, Logback, structured JSON)](structured-logging.md) | writes one JSON object per log line, with a request id and trace ids |
| 17 | [JUnit (Jupiter)](junit.md) | runs every test method and reports which passed |
| 18 | [RestClient (calling another service over HTTP)](rest-client.md) | makes the call from the price service to the provider, with a connect and a read timeout |
| 19 | [AssertJ](assertj.md) | the readable assertions (`assertThat(x).isEqualTo(y)`) used in every test |
| 20 | [Spring Framework resilience annotations (@Retryable, @ConcurrencyLimit)](spring-resilience.md) | bounded retries and a limit on concurrent provider calls, as annotations |
| 21 | [Mockito](mockito.md) | creates stand-in objects ("mocks") for the collaborators a test does not want to run |
| 22 | [Resilience patterns (timeouts, bounded retries, load shedding, stale cache)](resilience-patterns.md) | the ideas behind every decision in the price service, in one place |
| 23 | [Spring Boot Test (test slices, MockMvc, full-context tests)](spring-boot-test.md) | starts as much of the application as a test needs, from the web layer only to the whole service |
| 24 | [Virtual threads](virtual-threads.md) | lets the price service wait on many slow provider calls without using up threads |
| 25 | [ArchUnit](archunit.md) | turns the dependency rule between layers into a test that fails the build |
| 26 | [Micrometer metrics and OpenTelemetry traces](micrometer-opentelemetry.md) | counts and times what the price service does, and records the path of each request |
| 27 | [Grafana, Prometheus and Tempo (the grafana/otel-lgtm image)](grafana-stack.md) | receives the service's metrics and traces and shows them on a dashboard |
| 28 | [JaCoCo (code coverage)](jacoco.md) | measures which lines the tests ran and fails the build below a limit |
| 29 | [The JDK's HTTP server and HTTP client (the provider and the load generator)](jdk-http.md) | the plain-Java fake provider and the load generator, built on classes that ship with Java |
| 30 | [Spotless and google-java-format](spotless-google-java-format.md) | formats every Java file the same way, and fails the build if a file is not formatted |
| 31 | [Checkstyle](checkstyle.md) | enforces the written rules a formatter cannot: names, imports, public Javadoc, no leftovers |
| 32 | [EditorConfig](editorconfig.md) | tells every editor the same indentation, line ending and charset for each file type |
| 33 | [Git and .gitignore](git-and-gitignore.md) | keeps the history of the repository and decides which files are never recorded |
| 34 | [Dockerfile (container images)](dockerfile.md) | builds the image that runs the service: a multi-stage build ending in a small, non-root runtime |
| 35 | [Docker Compose](docker-compose.md) | starts the service, the fake provider and the Grafana stack together with one command |
| 36 | [YAML](yaml.md) | the file format of `docker-compose.yml` and the GitHub Actions workflow, and of the Grafana provisioning file |
| 37 | [Make and the Makefile](make.md) | gives every task a short name: `make up`, `make run`, `make test`, `make lint`, `make down` |
| 38 | [Bash (the scripts)](bash.md) | the scripts under `scripts/` that run the lesson's scenario, run the benchmark and capture traces |
| 39 | [curl](curl.md) | sends HTTP requests from the command line; the scripts and README examples use it |
| 40 | [jq](jq.md) | reads and reshapes JSON on the command line |
| 41 | [GitHub Actions](github-actions.md) | runs lint, tests, the dependency audit and the image build on every push |
| 42 | [OSV-Scanner (dependency audit)](osv-scanner.md) | checks every library the project uses against a database of known vulnerabilities |

## If you have ten minutes

Read these three, in this order:

1. [RestClient (calling another service over HTTP)](rest-client.md): makes the call from the price service to the provider, with a connect and a read timeout
1. [Spring Framework resilience annotations (@Retryable, @ConcurrencyLimit)](spring-resilience.md): bounded retries and a limit on concurrent provider calls, as annotations
1. [Resilience patterns (timeouts, bounded retries, load shedding, stale cache)](resilience-patterns.md): the ideas behind every decision in the price service, in one place
