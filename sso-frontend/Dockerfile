# The edge image: build the SPA bundle, then serve it AND reverse-proxy the backend with nginx, so the
# browser sees ONE origin (same-origin session cookie + CSRF + OIDC/SAML issuer preserved).
# Build context is sso-frontend/:  docker build -t mini-sso-frontend sso-frontend/

# --- Stage 1: build the standalone SPA bundle (dist/) ---
FROM node:22 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . ./
RUN npm run build   # tsc && vite build -> dist/ (outDir in vite.config.ts)

# --- Stage 2: nginx edge ---
FROM nginx:1.27-alpine
RUN apk add --no-cache curl
# The edge config: static SPA with history-mode fallback + reverse proxy of the backend's API/protocol paths.
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
# nginx's own liveness (the SPA shell must be servable); the backend has its own healthcheck.
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -fsS http://localhost/ > /dev/null || exit 1
