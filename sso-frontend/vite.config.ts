import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// Backend (IdP) endpoints proxied to the Spring app during development so the SPA shares
// the IdP origin's session cookie. In production the SPA is a standalone static bundle (dist/)
// served by the nginx edge, which reverse-proxies these SAME paths to the backend — so dev and
// prod route identically (see sso-frontend/nginx/default.conf).
const backend = "http://localhost:9000";
const proxy = Object.fromEntries(
  // Note: "/login/webauthn" only (NOT all of "/login", which is a client-side SPA route);
  // "/webauthn" covers passkey register/authenticate options + registration.
  ["/api", "/oauth2", "/saml2", "/scim", "/userinfo", "/.well-known", "/connect", "/actuator",
   "/webauthn", "/login/webauthn"].map((p) => [
    p,
    { target: backend, changeOrigin: true, cookieDomainRewrite: "localhost" },
  ]),
);

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { "@": path.resolve(__dirname, "src") } },
  server: { port: 5173, proxy },
  // SPA loaded once behind the login gate — a single ~770KB (gzip ~220KB) bundle is fine, and
  // route-splitting an app served from one origin buys little. Raise the generic 500KB warning.
  build: { outDir: "dist", emptyOutDir: true, chunkSizeWarningLimit: 1000 },
});
