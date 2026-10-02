# syntax=docker/dockerfile:1
#
# Two-stage build: compile in a big image, ship only the result in a small one.
#   docker build -t patch-notes-aggregator .
#   docker run -p 8080:8080 -e JWT_SECRET=<32+ characters> patch-notes-aggregator
# (compose.prod.yaml runs it together with MySQL.)

# ======================================================================================================
# Stage 1 - build. A full JDK. Thrown away afterwards; nothing from here ships except the jar.
# ======================================================================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Docker caches the result of every instruction (a "layer") and reuses it while the instruction and the files
# it copies are unchanged. So copy the files that change RARELY first and the ones that change OFTEN last:
# editing a source file then re-runs only the steps below, not the slow dependency download.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# The cache mount keeps Maven's download folder between builds on the same machine.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
COPY web/ web/
# One command builds everything: Java, then (via the frontend plugin) a project-local Node, `npm ci`, the web UI
# tests and the production bundle, all packed into a single runnable jar. Java tests run in CI, not on every image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && cp target/patch-notes-aggregator-*.jar /workspace/app.jar

# ======================================================================================================
# Stage 2 - runtime. A JRE only (no compiler, no Maven, no Node, no source code): smaller and less to attack.
# ======================================================================================================
FROM eclipse-temurin:21-jre AS runtime

# curl is only for the health check below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run as root: if the app were ever compromised, the attacker would not own the container.
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app

# Only the finished jar is copied from the build stage, owned by the unprivileged user.
COPY --from=build --chown=app:app /workspace/app.jar app.jar
# Where the default (H2) database would live. Real deployments use MySQL; mount a volume here to keep H2 data.
RUN mkdir /app/data && chown app:app /app/data
USER app

LABEL org.opencontainers.image.source="https://github.com/Vandrae/patch-notes-aggregator" \
      org.opencontainers.image.description="Patch Notes Aggregator: Steam patch notes for the games you follow"

EXPOSE 8080

# "Running" is not "ready": ask the app itself. Docker (and compose) mark the container unhealthy if this keeps failing.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# Exec form (a JSON array) so the JVM is process 1 and receives the stop signal for a graceful shutdown.
# MaxRAMPercentage sizes the heap from the container's memory limit; the JVM exits on OutOfMemoryError so that
# the orchestrator restarts it instead of leaving a half-dead process.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
