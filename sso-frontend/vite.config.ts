import { defineConfig } from "vitest/config";
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
    // changeOrigin:false FORWARDS the browser's real Host (e.g. octatco.localhost:5173) to the backend.
    // The IdP derives the tenant + per-tenant OIDC issuer + the admin-console same-origin redirect from that
    // Host (dev has forward-headers-strategy:none, so X-Forwarded-Host is not an option) — rewriting it to
    // localhost:9000 would lose the subdomain, so the org never resolves and /oauth2/authorize 400s.
    { target: backend, changeOrigin: false, cookieDomainRewrite: "localhost" },
  ]),
);

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { "@": path.resolve(__dirname, "src") } },
  server: { port: 5173, proxy },
  // SPA loaded once behind the login gate — a single ~770KB (gzip ~220KB) bundle is fine, and
  // route-splitting an app served from one origin buys little. Raise the generic 500KB warning.
  build: { outDir: "dist", emptyOutDir: true, chunkSizeWarningLimit: 1000 },
  // jsdom gives the tests a real localStorage and DOM — the sign-in memory (lib/loginMemory.ts) and the
  // entry screens are built on them. passWithNoTests:false so a broken glob fails loudly instead of
  // reporting success on zero collected tests; restoreMocks so a spy (e.g. a throwing localStorage) can
  // never leak into a later test.
  test: {
    environment: "jsdom",
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    setupFiles: ["src/test/setup.ts"],
    passWithNoTests: false,
    restoreMocks: true,
  },
});
