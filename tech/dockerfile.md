# Dockerfile (container images)

## What it is

A **container image** is a packaged filesystem plus a start command: your program, its Java runtime and the files it needs. A **container** is a running copy of an image, isolated from the rest of the machine. A **Dockerfile** is the recipe for an image: a list of instructions, run top to bottom. Each instruction makes a **layer**; Docker caches layers and reuses them when nothing above them changed.

## Why this repository uses it

The project rule says anything that serves traffic gets a Dockerfile with a pinned base image and a non-root user. A **multi-stage** build keeps the compiler and Maven cache out of the final image, so the image holds only a Java runtime and the application.

## Where it appears

| Path | Role |
| --- | --- |
| `price-service/Dockerfile` | the price service image |
| `provider/Dockerfile` | the fake provider image |
| `.dockerignore` | files not sent to the builder |
| `docker-compose.yml` | builds and runs the image (see `docker-compose.md`) |

## How it works underneath

Words first. A **container image** is a read-only package: a small Linux filesystem plus the program and its Java runtime. A **container** is a running copy of an image, isolated from the rest of your machine: it sees its own files, its own processes and its own network address. **Docker** is the program (the Docker engine, running in the background) that builds images and runs containers. A **registry** (Docker Hub, GitHub Container Registry) is a server that stores images; `FROM` pulls from one. A **layer** is one step of a build, saved so that Docker can reuse it when nothing above it changed.

1. `docker build` sends the **build context** (the folder, minus `.dockerignore` entries) to the Docker engine.
2. `FROM image AS name` starts a stage from a base image. A second `FROM` starts a new, empty stage; `COPY --from=name` takes files from an earlier stage. Only the last stage becomes the image.
3. `@sha256:...` after an image name is a **digest**: it pins the exact bytes, so a rebuild next year uses the same base. A tag like `21-jre-noble` can move.
4. `RUN` executes a command at build time and saves the result as a layer. `COPY` copies files. `ENV` sets environment variables for the running container. `USER` sets who the process runs as. `ENTRYPOINT` is the command the container starts.
5. `RUN --mount=type=cache,target=/root/.m2` gives the build a cache folder that survives between builds, so Maven does not download every library each time.

## Walkthrough of this repository's use

```dockerfile
FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -DskipTests -pl price-service -am package \
 && java -Djarmode=tools -jar price-service/target/price-service-0.1.0.jar extract --layers --destination /workspace/layers

FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/layers/dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/application/ ./
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "price-service-0.1.0.jar"]
```

**Stage 1, `build`.**

- `FROM eclipse-temurin:25-jdk-noble@sha256:... AS build`: a JDK image pinned by digest.
- `COPY . .` copies the whole repository (the `.dockerignore` keeps `target/` and `.git/` out) because the Maven reactor needs every module's POM.
- `RUN ... ./mvnw -B -ntp -q -DskipTests -pl price-service -am package` builds only the price service and what it needs (`-pl` picks the module, `-am` "also make" its dependencies), then extracts the layers as in the single-module projects. The provider's Dockerfile builds `-pl provider -am` and copies the one jar.

**Stage 2, the runtime.**
- `FROM eclipse-temurin:25-jre-noble@sha256:...`: a JRE only, much smaller than the JDK.
- `RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app` creates a user without a login or home folder. `USER 10001:10001` makes the process run as that user, not as root, so a bug in the service cannot change the system inside the container.
- `COPY --from=build --chown=app:app ...` takes the layers from stage 1 and gives them to that user. Four `COPY` lines, one per layer, in the order from least to most changing.
- `EXPOSE 8080` documents the port. It does not publish it; compose does.

- `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`: read by every JVM: use up to 75 percent of the container's memory, and exit if memory runs out so the orchestrator can restart a clean process.

- `ENTRYPOINT ["java", "-jar", "price-service-0.1.0.jar"]`: the JSON form runs the process directly, so it receives the stop signal (SIGTERM) and can shut down gracefully. After `extract`, the jar in `/app` is a small launcher that finds the layered libraries next to it.

**The provider's Dockerfile** (the second image):

```dockerfile
FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -DskipTests -pl provider -am package

FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/provider/target/provider-0.1.0.jar provider.jar
USER 10001:10001
ENV PORT=8081 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "provider.jar"]
```

It has the same shape with three differences: `-pl provider -am` builds only the provider; there is no layer extraction, because the provider is a plain jar with no libraries inside; and `COPY --from=build ... provider-0.1.0.jar provider.jar` copies that one file and renames it. `ENV PORT=8081` sets the port the provider listens on (its `main` reads the environment variable `PORT`), and `JAVA_TOOL_OPTIONS` uses 50 percent of the container's memory because this process is small. A **port** is the number a program listens on for network connections; `EXPOSE 8081` documents it.

**`.dockerignore`.** When you run `docker build`, the whole folder (the **build context**) is sent to the Docker engine first. The file `.dockerignore` lists what must not be sent:

```text
**/target/
.git/
.env
.idea/
docs/
*.md
```

Each line is a pattern. `target/` is Maven's build output (rebuilt inside the image); `.git/` is the history; `.env` holds local settings and possibly a password, which must never end up in an image layer; `.idea/` is editor state; `docs/` and `*.md` are not needed to build the image, and `**/target/` matches a `target` folder at any depth, which matters because the build copies the whole multi-module repository. A smaller context sends faster and keeps cache hits: changing a README does not invalidate the `COPY` layers.

## Try it yourself

1. `docker build -t demo . -f price-service/Dockerfile` builds the image (the first build downloads a lot).
2. `docker image ls demo` shows its size; compare with the JDK image: `docker image ls eclipse-temurin`.
3. `docker run --rm --entrypoint id demo` prints `uid=10001(app)`: not root.
4. `docker history demo` lists the layers and their sizes.

## Mistakes people make

- Copying the source before the POM: every code change invalidates the dependency layer.
- Running as root in the container.
- Using a moving tag (`latest`) for the base: two builds differ.
- Putting secrets in `ENV` or in a layer: they stay in the image history.
- Writing the shell form `ENTRYPOINT java -jar app.jar`: the shell becomes process 1 and swallows the stop signal.

## Check yourself

1. What is a multi-stage build for? *To keep build tools out of the final image.*
2. What does a digest pin? *The exact bytes of the base image.*
3. Why `USER 10001`? *Not to run as root.*
4. Why does the order of `COPY` lines matter? *Layer caching: rarely changed files first.*
5. What does the JSON form of `ENTRYPOINT` buy? *The Java process receives the stop signal directly.*

## Further reading

- Dockerfile reference: https://docs.docker.com/reference/dockerfile/
- Multi-stage builds: https://docs.docker.com/build/building/multi-stage/
- Spring Boot 4.1.2, efficient container images (layers): https://docs.spring.io/spring-boot/4.1.2/reference/packaging/container-images/efficient-images.html
