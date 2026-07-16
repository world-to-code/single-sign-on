# API-only backend image. The SPA is built and served separately by the nginx edge (see sso-frontend/),
# so this image no longer bundles or serves any static content.
# Build context is sso-backend/:  docker build -t mini-sso-backend sso-backend/

# --- Stage 1: build the Spring Boot jar, then explode it into layers ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Pre-fetch dependencies as a cached layer (best-effort; ignore failure so a partial graph still caches).
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
# Package ONLY the deployable artifact — `bootJar`, not `build`: `build` would run the full test suite, which
# here is Testcontainers-based (needs a Docker daemon this build stage does not have) and belongs in CI, not in
# image packaging. `bootJar` alone also emits a single executable (fat) jar — the `-plain` library jar only
# appears with `build`/`assemble` — so the glob below is unambiguous.
RUN ./gradlew --no-daemon clean bootJar

# --- Stage 2: runtime (Alpine JRE — small, minimal CVE surface vs the Ubuntu base) ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# curl for the container HEALTHCHECK below (readiness probe on the management port).
RUN apk add --no-cache curl
# Run as a NON-ROOT user (prod-grade: limits blast radius if the app is compromised).
RUN addgroup -S sso && adduser -S -G sso -H sso
# Persisted SAML keystore (mount a volume to keep it across restarts); must be writable by the non-root user.
RUN mkdir -p /data && chown sso:sso /data
VOLUME ["/data"]
ENV SPRING_PROFILES_ACTIVE=prod
# Only the runtime artifact ships — no gradle, no JDK, no source (multi-stage keeps the image minimal).
COPY --from=build --chown=sso:sso /app/build/libs/*.jar /app/app.jar
USER sso
# 9000 = application (behind the edge), 9001 = management (health/metrics/prometheus; keep off the public edge).
EXPOSE 9000 9001
# Readiness gates traffic; management probes are enabled in application.yml. Generous start-period for JVM boot.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=6 \
  CMD curl -fsS http://localhost:9001/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
