# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -U -pl cia-api -am package -DskipTests --batch-mode --no-transfer-progress

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system cia \
    && useradd --system --gid cia --home-dir /app --shell /usr/sbin/nologin cia

COPY --from=build /workspace/cia-api/target/cia-api-*.jar /app/cia-api.jar

ENV SERVER_PORT=8090
EXPOSE 8090

USER cia:cia

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:${SERVER_PORT}/actuator/health/liveness >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/cia-api.jar \"$@\"", "--"]
