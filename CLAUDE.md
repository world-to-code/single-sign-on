# CLAUDE.md — Mini SSO (monorepo, shared rules)

Central Identity Provider: a Spring Boot backend that serves an all-React SPA from its own
origin. This file holds rules **common to both workspaces**. Directory-scoped rules live in
`sso-backend/CLAUDE.md` and `sso-frontend/CLAUDE.md` and take precedence for files under them —
do not duplicate them here.

## Layout

```
sso-backend/    Spring Boot modular monolith (the deployable) — see sso-backend/CLAUDE.md
sso-frontend/   React + Vite SPA; `npm run build` emits a standalone `dist/` bundle, served by the nginx edge
scripts/        Python live-flow verifiers (OIDC / SAML / SCIM / admin)
test-client/    Standalone OIDC RP for manual testing
docs/           Project docs (e.g. commit-convention.md)
docker-compose.yml   Postgres :5432, MailHog :8025 (dev infra)
```

Dev topology: backend on `:9000`; frontend dev server on `:5173` (Vite proxies API/OIDC/SAML/
webauthn to `:9000`). Prod: **split** — the SPA is a standalone static bundle served by an **nginx
edge** that reverse-proxies those same paths to the **API-only backend**, so the browser still sees
ONE origin (same-origin session cookie + CSRF + host-derived per-tenant issuer preserved). Per-service
Dockerfiles (`sso-backend/Dockerfile`, `sso-frontend/Dockerfile`); `docker-compose.prod.yml` runs the
full split stack locally (edge on `:80`). `SSO_ISSUER` must be the public edge origin.

## Git & commits (STRICT)

- **Commit directly to `main`. Never create feature branches.**
- **Never add a `Co-Authored-By` (or any co-author) trailer** to commit messages.
- Follow **Conventional Commits** — see `docs/commit-convention.md`
  (`type(scope): subject`; imperative, lowercase, no trailing period, ≤72 chars). Keep
  logically-separate concerns in separate commits.
- Only commit/push when the user asks. Before destructive git (force push, reset --hard),
  state intent; prefer `--force-with-lease`.

## Engineering defaults (both stacks)

- **Design object-oriented and respect SOLID.** Small, single-responsibility units; depend on
  abstractions; compose over inherit.
- **No dead code.** Remove unused methods/vars/imports/components as you go; delete rather than
  comment out.
- **Least scope, most reversible.** Match surrounding style (naming, structure, comment density);
  don't refactor unrelated code unless asked.
- **Verify before claiming done.** Build/test the change; report failures with output. Don't
  hedge once verified, don't overstate when not.
- **Security is not optional.** Preserve authz checks, encryption-at-rest, non-revealing errors,
  lockout, CSRF/session hardening across refactors — never drop them for convenience.
- **Reference types via imports, never inline fully-qualified names** (applies to both TS and Java).

## Formatting

`.idea/.editorconfig` is the source of truth: UTF-8, LF, final newline, trim trailing whitespace.
Java/backend = 4-space indent (≤120 cols); TS/JSON/CSS/frontend = 2-space. Comments, docs, and
commit messages are written in **English**.

## Verifying end to end

- Infra: `docker compose up -d` (Postgres + MailHog).
- Backend: `cd sso-backend && ./gradlew test` (Testcontainers — needs Docker).
- Frontend: `cd sso-frontend && npm run build` (type-check + bundle).
- Live flows: `python3 scripts/{oidc_authcode_flow,saml_sso_flow,admin_api_flow}.py`.
