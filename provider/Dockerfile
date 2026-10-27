# Build from the repository root: docker build -f provider/Dockerfile .
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
