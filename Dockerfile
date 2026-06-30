# Self-contained multi-stage build for the single deployable (the backend serves the SPA).
# Build context is the repo ROOT so both services are available:  docker build -t mini-sso .

# --- Stage 1: build the React SPA (sso-frontend) ---
FROM node:22 AS frontend
WORKDIR /app/sso-frontend
COPY sso-frontend/package.json sso-frontend/package-lock.json ./
RUN npm ci
COPY sso-frontend/ ./
# Emit to a local dist here; the vite config's outDir points at the backend tree which
# isn't present in this stage, so override it and copy the bundle in the backend stage.
RUN npx tsc -b && npx vite build --outDir dist --emptyOutDir

# --- Stage 2: build the Spring Boot jar (sso-backend), bundling the SPA into static resources ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY sso-backend/gradlew sso-backend/settings.gradle sso-backend/build.gradle ./
COPY sso-backend/gradle ./gradle
# Pre-fetch dependencies (cached layer)
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY sso-backend/src ./src
COPY --from=frontend /app/sso-frontend/dist ./src/main/resources/static
RUN ./gradlew --no-daemon clean bootJar

# --- Stage 3: runtime ---
FROM eclipse-temurin:21-jre
WORKDIR /app
# Persisted SAML keystore (mount a volume to keep it across restarts)
VOLUME ["/data"]
ENV SPRING_PROFILES_ACTIVE=prod
COPY --from=build /app/build/libs/*.jar /app/app.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
