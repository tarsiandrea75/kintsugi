# syntax=docker/dockerfile:1
# fault-injector image for the compose lab (infra/compose.yaml). Base image tags are pinned in ADR-008; the Docker CLI
# and compose plugin versions are pinned in ADR-009. Tests run outside Docker (./mvnw test), so the build skips them.

FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /build
# The pom alone first: the dependency layer is rebuilt only when the pom changes, not on every source edit.
# The cache mount keeps ~/.m2 across builds, so even a pom change downloads only what is new.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
	&& cp target/fault-injector-*.jar /build/app.jar

FROM eclipse-temurin:21.0.12.1_1-jre-noble
# The two static binaries F3 and F4 need (ADR-009), so the JVM base image is unchanged and no package repository is
# added. The Docker CLI 29.8.2 comes from the official CLI image. The compose plugin v5.6.0 comes from Compose's own
# binary image: docker:29.8.2-cli still bundles v5.5.1. The plugin path is one of the CLI's default plugin directories.
COPY --from=docker:29.8.2-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker/compose-bin:v5.6.0 /docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
# Dedicated unprivileged user; the image's own "ubuntu" user belongs to the sudo group. It is not in any group that
# owns the Docker socket: the socket's group id differs per host, so compose adds it at run time with group_add
# (ADR-009, "Socket access without root").
RUN useradd --system --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
# The CLI reads its config from $DOCKER_CONFIG; the user has no home, so point it at a writable, empty place.
ENV DOCKER_CONFIG=/tmp/.docker
EXPOSE 8084
# curl ships with the Temurin image; compose uses it for the actuator health check.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
