# syntax=docker/dockerfile:1.7
#
# Production image for the CIA backend (cia-api assembly + all modules).
# Build context is cia-backend/ — self-contained reactor (parent pom + mvnw +
# all modules live here, no module references a path outside this dir).
#
#   docker build -t cia-backend -f cia-backend/Dockerfile cia-backend
#
# Multi-stage: a Maven/JDK builder produces the executable jar; a slim JRE
# runtime runs it as a non-root user with a liveness healthcheck.

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY . .
# -pl cia-api -am builds cia-api and only the modules it needs. The BuildKit
# cache mount keeps ~/.m2 warm across builds. Tests run in CI (mvn verify),
# not here — the image build stays fast and deterministic.
#
# Retry flags make the build resilient to transient Maven Central failures
# (e.g. a truncated artifact download): the wagon http retry handler retries
# each failed request up to 3 times. Without these a single dropped connection
# fails the whole image build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -U -pl cia-api -am package -DskipTests --batch-mode --no-transfer-progress \
        -Dmaven.wagon.http.retryHandler.count=3 \
        -Dmaven.wagon.httpconnectionManager.ttlSeconds=60

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# curl for the HEALTHCHECK; a dedicated non-root system user for defence in depth.
# apt-get upgrade pulls the latest jammy security patches at build time (e.g. the
# OpenSSL/libssl3 fix for CVE-2026-45447) so the runtime image clears the enforcing
# backend-image.yml Trivy gate even when the base tag lags behind a fresh disclosure.
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system cia \
    && useradd --system --gid cia --home-dir /app --shell /usr/sbin/nologin cia

COPY --from=build /workspace/cia-api/target/cia-api-*.jar /app/cia-api.jar

ENV SERVER_PORT=8090
EXPOSE 8090

USER cia:cia

# Liveness probe — requires management.endpoint.health.probes.enabled=true
# (set in application.yml). The /liveness group is permitAll in SecurityConfig.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:${SERVER_PORT}/actuator/health/liveness >/dev/null || exit 1

# Pass JAVA_OPTS through; "$@" forwards any extra args (e.g. --spring.profiles.active).
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/cia-api.jar \"$@\"", "--"]
