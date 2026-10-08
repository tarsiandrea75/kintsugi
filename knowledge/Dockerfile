# syntax=docker/dockerfile:1
# Knowledge indexer image for the compose lab (ADR-002), built like mcp-tools' image. Base image tags are pinned in
# ADR-008. Tests run outside Docker (./mvnw test), so the build skips them.
#
# A one-shot job: it indexes the runbooks mounted read-only at /runbooks into the knowledge store, then exits with 0,
# or 1 on any failure (the previous index stays intact). Compose wiring is T5 of odd/tasks/runbook-rag.md.

FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /build
# The pom alone first: the dependency layer is rebuilt only when the pom changes, not on every source edit.
# The cache mount keeps ~/.m2 across builds, so even a pom change downloads only what is new.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
	&& cp target/knowledge-*.jar /build/app.jar

FROM eclipse-temurin:21.0.12.1_1-jre-noble
# Dedicated unprivileged user; the image's own "ubuntu" user belongs to the sudo group.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
# The only input is docs/runbooks/, mounted read-only at /runbooks by compose; openspec/ and odd/ are never mounted.
# No VOLUME on purpose: a forgotten mount must fail the run (no runbooks found), not index an empty directory.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
