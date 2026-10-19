# syntax=docker/dockerfile:1

# =============================================================================
# Build stage — full Maven, compiles and repackages the executable jar.
# =============================================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# .dockerignore strips target/, src/test/ and .git from the context, so this layer only
# ever sees pom.xml and the main sources.
#
# The .m2 cache mount is the important part: dependencies persist across builds outside the
# image's layer cache, so changing a Java file does not re-download Spring Boot. That is why
# there is no `dependency:go-offline` step — with a shared cache it buys nothing.
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -Dmaven.test.skip=true

# =============================================================================
# Runtime stage — just a JRE and the jar.
# =============================================================================
# glibc (jammy), not Alpine: serverping resolves hostnames for a living, and musl's resolver
# handles search domains and ndots differently from glibc. For a network monitor, saving
# ~80 MB is not worth occasionally not finding a host.
FROM eclipse-temurin:17-jre-jammy

# curl exists only so HEALTHCHECK gets a real /actuator/health verdict. Without it the
# healthcheck could only test that a socket is open, which says nothing about whether the
# app is actually able to serve.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 1001 serverping

# Spring relaxed binding maps SERVER_ADDRESS onto server.address, and this default in
# application.properties is 127.0.0.1. Inside a container that makes the published port
# unreachable — nothing listens where Docker forwards to. The container boundary is the
# isolation here; there is still no authentication, so do not publish 8080 to the internet.
ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=8080

WORKDIR /app

# The wildcard matches only the repackaged fat jar: the plugin's backup file is
# serverping-*.jar.original, which does not end in .jar. If a build ever produced two
# matches COPY would fail loudly rather than pick an arbitrary one.
COPY --from=build /build/target/serverping-*.jar app.jar

# Non-root at 1001. Nothing here needs a capability: TCP connects to high ports are
# unprivileged (only ICMP would have required CAP_NET_RAW, which is one of several reasons
# the checker speaks TCP). No local data directory either — persistence is PostgreSQL.
USER serverping

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# MaxRAMPercentage rather than -Xmx: the JVM otherwise sizes its heap against the container's
# whole cgroup memory, gets itself OOM-killed, and the failure shows up as a random restart.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
