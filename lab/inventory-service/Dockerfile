# syntax=docker/dockerfile:1
# inventory-service image for the compose lab (infra/compose.yaml). Base image tags are pinned in ADR-008.
# Tests run outside Docker (./mvnw test), so the build skips them.

FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /build
# The pom alone first: the dependency layer is rebuilt only when the pom changes, not on every source edit.
# The cache mount keeps ~/.m2 across builds, so even a pom change downloads only what is new.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
	&& cp target/inventory-service-*.jar /build/app.jar

FROM eclipse-temurin:21.0.12.1_1-jre-noble
# Dedicated unprivileged user; the image's own "ubuntu" user belongs to the sudo group.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
EXPOSE 8083
# curl ships with the Temurin image; compose uses it for the actuator health check.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
