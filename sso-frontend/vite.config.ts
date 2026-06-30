import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// Backend (IdP) endpoints proxied to the Spring app during development so the SPA shares
// the IdP origin's session cookie. The production build is emitted into Spring's static
// resources and served directly from the IdP origin.
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
  build: { outDir: "../sso-backend/src/main/resources/static", emptyOutDir: true },
});
