# Mini SSO System — Multi-Tenant Identity Provider

*🌏 [한국어 README](README_KR.md)*

A from-scratch, production-leaning **multi-tenant Single Sign-On Identity Provider (IdP)**.
Each **organization is a tenant** with its own subdomain, users, policies, apps, and signing
keys; other applications delegate authentication to it over **OIDC**, **SAML 2.0**, or
**SCIM 2.0**, and every human sign-in is protected by **policy-driven multi-factor
authentication**. Built as a **Spring Modulith modular monolith** on **Spring Boot 4 /
Spring Security 7**, with a **React** admin + login console served from the same origin as a
single deployable, and **PostgreSQL Row-Level Security** as the hard boundary between tenants.

> **In one sentence:** each company gets its own isolated IdP at `its-slug.example.com` — its
> own users, MFA, policies, apps and audit trail — while a thin platform layer owns the tenant
> registry, and RLS + host-derived issuers keep every tenant's data and tokens strictly separate.

---

## Table of contents

- [What it does (at a glance)](#what-it-does-at-a-glance)
- [Multi-tenancy](#multi-tenancy--every-organization-is-an-isolated-tenant)
- [Architecture](#architecture)
- [Authentication](#authentication--how-a-user-proves-who-they-are)
- [Authorization](#authorization--what-a-user-is-allowed-to-do)
- [Cryptography & key management](#cryptography--key-management)
- [Sessions & data handling](#sessions--data-handling)
- [Security hardening](#security-hardening)
- [Federation protocols](#federation-protocols)
- [Tech stack & tools](#tech-stack--tools)
- [Repository layout](#repository-layout)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Key endpoints](#key-endpoints)
- [Verifying the flows](#verifying-the-flows)
- [Production](#production)
- [Conventions](#conventions)

---

## What it does (at a glance)

| Capability | Summary |
|---|---|
| **Multi-tenancy** | **Organization = tenant.** Per-tenant subdomain (`{slug}.base`), host-derived OIDC issuer + signing key, and **PostgreSQL RLS** isolating every org-scoped table. Global/shared rows (`org_id IS NULL`) are visible everywhere; a tenant sees only its own. |
| **Two-tier admin** | A **platform super-admin** owns the tenant registry + global config and **drills into** a tenant to manage it (audited); a **tenant admin** (`ROLE_ORG_ADMIN`) fully manages **its own** org — users, apps, roles, policies, keys, SCIM, its own audit. |
| **Tenant onboarding** | **Public self-service signup** (email-verification-first: nothing is provisioned until the link is redeemed) *and* **admin-initiated onboarding** (provision up front + invite). Creating a tenant auto-provisions its baseline (default session + auth policy, "All Users" group). |
| **OIDC Provider** | OAuth 2.1 + OpenID Connect 1.0 — discovery, JWKS (rotatable RS256), authorization-code + PKCE, client-credentials, refresh tokens, consent, UserInfo. **Per-tenant issuer** derived from the subdomain. |
| **SAML 2.0 IdP** | OpenSAML 5 — metadata, `AuthnRequest` over HTTP-Redirect/POST, **signed assertions**; per-tenant relying-party registry. |
| **SCIM 2.0 server** | Inbound provisioning of Users/Groups (`/scim/v2`, bearer auth); per-tenant tokens provision **into their own org**. |
| **Multi-factor auth** | **Tenant-first**, identifier-first login with **password, TOTP, email OTP, and FIDO2 passkeys** (incl. per-org passwordless passkey first-factor), ordered by a per-user **authentication policy**. |
| **Step-up / elevation** | RFC 9470 fresh re-authentication for sensitive actions; **token-based privilege elevation** to enter the admin console, bounded by the acting tenant's session policy. |
| **RBAC + PBAC** | Roles gate URLs; fine-grained permissions gate operations (`@PreAuthorize`); instance-level (ABAC) checks scope every object to the acting tenant. |
| **Self-service** | "My Profile": registered factors/passkeys, email-verification status, active-session list with per-device revoke. |
| **Admin console** | Same SPA — user lifecycle, OIDC clients, SAML relying parties, groups, roles, resources, session/auth policies, network zones, audit log, SCIM tokens, key rotation — all tier-scoped to the acting tenant. |

---

## Architecture

A single deployable, structured internally as a **Spring Modulith modular monolith**: each
domain (`user`, `organization`, `authpolicy`, `session`, `oidc`, `saml`, `scim`, `admin`,
`onboarding`, `resource`, …) is an enforced module exposing only a root API (interfaces + record
DTOs); entities and repositories never cross a module boundary, and `ModularityTests` keeps the
boundaries honest. The React SPA is built **into the backend's static resources** and served from
the IdP origin, so it shares the IdP's **session cookie** (no cross-origin token juggling for the
first-party UI). Isolated `SecurityFilterChain`s separate concerns: the OAuth2 Authorization
Server (protocol endpoints, with a **per-tenant host filter**), the SCIM chain (stateless bearer),
and the app/SPA chain (session + CSRF + tenant-context + RLS).

```
             {slug}.example.com                ┌───────────────────────────────────────────────┐
  OIDC RP  ──/oauth2/*,/.well-known,/userinfo──►│   Spring Boot 4 IdP (Spring Modulith)         │
  SAML SP  ──/saml2/idp/{metadata,sso}─────────►│   ├─ Spring Security 7 (+ Auth Server)        │──JPA──► PostgreSQL 17
  Ext IdP  ──/scim/v2/*  (Bearer)──────────────►│   ├─ OpenSAML 5  (SAML IdP)                   │  Row-Level Security
  Browser  ──/ (React SPA) + /api/auth/* (cookie)►│   ├─ scim-sdk    (SCIM 2.0)                  │  (Flyway-managed)
  Browser  ──/api/admin/* (Bearer elevation)────►│   ├─ WebAuthn / TOTP / email OTP / RSA keys   │
             ▲ host → tenant + issuer + key      │   └─ TenantHostFilter + OrgContext + RLS bind │──SMTP──► MailHog (dev)
                                       └───────────────────────────────────────────────┘
```

The **request host selects the tenant**: `TenantHostFilter`/`OrgContextFilter` resolve
`{slug}.base` to an organization, bind it as the request's `OrgContext`, and set the PostgreSQL
`app.current_org` GUC so RLS scopes every query — while the OIDC issuer and signing key are
derived from that same host. Browser navigations to `/oauth2/authorize` or `/saml2/idp/sso` that
aren't yet authenticated are redirected to the SPA login, which completes the auth policy and then
**resumes the saved request**.

---

## Multi-tenancy — every organization is an isolated tenant

The **organization is the tenant**: one org per company, with its own users, groups, roles,
policies, apps, signing keys and audit trail. Isolation is enforced at three layers.

### 1. Data isolation — PostgreSQL Row-Level Security

Org-scoped tables carry an `org_id` and enforce an RLS policy of the shape
`org_id IS NULL OR org_id = current_setting('app.current_org')`: a tenant sees its own rows plus
the **global/shared** rows (`org_id IS NULL`, e.g. the seeded default policies), never another
tenant's. The runtime binds `app.current_org` per request from the resolved tenant. RLS is the
**hard boundary** — but it only binds when the app connects as a **non-superuser** role
(superusers bypass RLS), which the app enforces at startup (`sso.tenancy.require-non-superuser-role`).

A few tables are deliberately **RLS-free** because they are read on browser-less / pre-context
paths (login, logout propagation, SCIM): `app_user` and `audit_event`. These carry `org_id` as a
column and are isolated at the **application layer** instead (every read is explicitly org-scoped).

### 2. Tenant resolution — subdomain, host-derived issuer

Each tenant lives at `{slug}.base` (e.g. `acme.localhost:9000`, `acme.idp.example.com`). The host
resolves the org before authentication, so:

- the **OIDC issuer is per-tenant** (`http://acme.localhost:9000` → its own discovery + JWKS),
  backed by that tenant's signing key (with a global fallback);
- sessions are **host-bound** — a session established on one tenant's subdomain is rejected on
  another's (`TenantSessionHostGuard`), and an unknown subdomain 404s (`TenantUnknownSubdomainGuard`);
- login **auto-selects the org from the host**, so a member just signs in.

### 3. Two-tier administration — platform vs tenant, drill-in

| | Platform super-admin (`ROLE_ADMIN`) | Tenant admin (`ROLE_ORG_ADMIN`) |
|---|---|---|
| **Scope** | The **tenant registry** + global/shared config; **no merged all-tenant view**. | **Everything inside its own org** — users, groups, roles, apps, auth/session policies, network zones, signing keys, SCIM, its own audit. |
| **Cross-tenant** | Reaches a tenant's data only by **deliberate drill-in** (`X-Org-Context`, live-membership-checked, **audited** — okta-style). | None — bound to its own org by host + membership. |
| **Permissions** | `Permissions.PLATFORM` (org registry) — invisible and un-grantable to tenants. | `Permissions.tenantGrantable()` = everything else, each domain org-isolated. |

**Drill-in** lets a super-admin act *as* a tenant (RLS re-scoped to that org) with a thorough audit
trail recording who entered which tenant. Un-drilled, the super-admin sees only global rows.

### 4. Per-tenant baseline — provisioned on creation

Creating an organization publishes an event that provisions the tenant's **own editable defaults**:
a default **session policy** and **auth (login) policy** (org-owned, priority above the global
default so they win for that org, applied to every member), plus a per-org **"All Users" group**.
The **Default policy is a locked fallback** — its assignments, priority and enabled state are
frozen so an admin can never strand users by targeting the catch-all at an empty set. Admin-console
knobs (elevation-token TTL, IP allowlist) are per-tenant too; the admin session's lifetimes come
from the tenant's session policy.

---

## Authentication — how a user proves who they are

### Tenant-first, identifier-first, policy-driven flow

0. **Resolve the tenant** — the organization is taken from the **subdomain** (`{slug}.base`), or
   selected on the bare platform host, and pinned in the pre-auth session. The rest of login is
   scoped to it: a username shared across orgs authenticates against **this** org's account.
1. **Identify** — the user submits their email; membership of the resolved org is required
   (a non-member is rejected the same way as an unknown account, so the form leaks nothing).
2. **Resolve policy** — `AuthPolicyResolver` picks the authentication policy assigned to that
   user *within the login org* (or the org's default, else the global default). A policy is an
   **ordered list of required factors**; an org may enable **passwordless passkey** as the first
   factor.
3. **Walk the factors** — the SPA polls `GET /api/auth/session`, and for the current step calls
   the generic `POST /api/auth/factors/{factor}/{prepare,verify}` endpoints, which dispatch to a
   `FactorHandler` strategy. Each cleared factor grants a `FACTOR_*` authority.
4. **Complete** — once every required factor is satisfied, the session is upgraded with the
   `MFA_COMPLETE` authority and the user's real roles/permissions. Protected APIs require
   `MFA_COMPLETE`; OIDC/SAML authorization endpoints require it too.

### Supported factors (authentication tools)

| Factor | How it works | Notes |
|---|---|---|
| **Password** | Spring Security form/JSON login. | Hashes via a delegating encoder (bcrypt by default), upgrade-in-place ready. |
| **TOTP** | Self-contained **RFC 6238** (HMAC-SHA1, 6 digits, 30 s step, ±1 skew). Enrollment yields an `otpauth://` URI rendered as a **scannable QR** (ZXing). | Secret stored Base32, **encrypted at rest**; **replay-protected** (last-used time-step is burned). |
| **Email OTP** | 6-digit code mailed to the user (RFC 4648-style). | Configurable TTL + **attempt cap** (code burned after too many guesses); used for first-login email verification. |
| **FIDO2 / Passkeys** | **Spring Security 7 WebAuthn** module — passwordless/possession factor. | Register at login (enroll-at-login) or self-service; counts as a strong/hardware factor. |

### Onboarding (first login)

A new account is walked through **email verification → strong-factor enrollment**
(TOTP via QR and/or a passkey). Whether enrollment is allowed mid-login is a **per-policy**
flag (`allowEnrollmentAtLogin`), Okta-style.

### Step-up & re-authentication (RFC 9470)

Sensitive actions require a **deliberate, fresh re-authentication** — not merely a recent
login. `POST /api/auth/reauth/{factor}/verify` re-checks a strong factor and stamps a distinct
**`stepup_time`** marker on the session, separate from the login `auth_time`.

### Authentication-context claims (RFC 8176)

OIDC ID **and** access tokens carry how the user authenticated, so relying parties and the
admin gate can reason about strength and freshness:

- **`amr`** — methods used: `pwd`, `otp`, `hwk` (passkey), `mfa`.
- **`acr`** — `mfa` (two or more factors) or `sfa`.
- **`auth_time`** — when the login completed.
- **`stepup_time`** — when a deliberate step-up last occurred (present only after `/reauth`).

> These are carried through the JDBC authorization store as marker `GrantedAuthority`s
> (e.g. `AUTH_TIME_<epoch>`), which serialize safely where an arbitrary details object would not.

---

## Authorization — what a user is allowed to do

- **RBAC** — roles gate the admin URL space at the filter chain (`ROLE_ADMIN` = platform
  super-admin, `ROLE_ORG_ADMIN` = tenant admin).
- **PBAC** — fine-grained permissions (`user:update`, `key:rotate`, `audit:read`, …) gate
  individual operations via method-level `@PreAuthorize`. `Permissions.PLATFORM` (the tenant
  registry) is super-admin-only and un-grantable to tenants; everything else is
  **tenant-grantable** and org-isolated, so a tenant admin manages its own org fully but nothing
  beyond it.
- **ABAC (instance-level)** — every object referenced by a client-supplied id gets an
  ownership/scope check that composes with `and`: a tenant admin reaches only rows in its own org
  (`AdminAccessPolicy` + RLS + the org-tier guard), so there is no IDOR across tenants. Admin list
  views are tier-scoped — an un-drilled super-admin sees only global rows, a tenant admin only its
  own, and the audit log read resolves the acting tenant.
- **Admin console = privilege elevation.** Entering `/api/admin/**` requires a **fresh
  bearer access token** obtained through a dedicated first-party `admin-console` OIDC client
  (PKCE, host-agnostic so it works at any tenant subdomain). The `AdminElevationFilter` (additive,
  runs after RBAC/PBAC) accepts the request only when the token is:
  - issued by **this IdP at the request's own host** (`iss`) for the **`admin-console`** client (`azp`),
  - carrying the reserved **`admin`** scope,
  - asserting **`acr=mfa`** and a **fresh `stepup_time`** within the **acting tenant's** session-policy
    step-up window, with the token's own age bounded by the tenant's elevation-token TTL,
  - passing the tenant's admin-console **IP allowlist**,
  - and bound to the **current session subject** (`sub`) — so a token minted for another user
    or another client cannot elevate.

  This makes admin entry a genuine **token-based step-up elevation** rather than a cosmetic
  prompt. The reserved `admin` scope cannot be assigned to any other client.

---

## Cryptography & key management

| Concern | Mechanism |
|---|---|
| **Password hashing** | `DelegatingPasswordEncoder` (PHC-prefixed; **bcrypt** by default), so algorithms can be upgraded without a migration. |
| **Secrets at rest** | `SecretCipher` — authenticated **AES-256-GCM** (`Encryptors.delux`, `encg:` prefix). Key derived (PBKDF2) from a master password + salt supplied via env. Legacy AES-256-CBC (`enc:`) and bare plaintext are still **read** and transparently **upgraded to GCM** on next write. |
| **OIDC token signing** | Rotatable **RSA** keypairs (**RS256**) persisted in the DB; the **private key is encrypted** with `SecretCipher`. The active key signs; all keys are published via **JWKS** so tokens issued before a rotation still verify. Rotation via the admin API. |
| **SAML assertion signing** | Self-signed **X.509** (RSA, `SHA256withRSA`) in a PKCS#12 keystore; rotatable. Assertions are **marshalled before signing** and include `KeyInfo` so SPs can verify. |
| **TOTP secrets** | Stored Base32, encrypted at rest via `SecretCipher`. |
| **SCIM tokens** | Issued once, stored only as a **SHA-256 hash**, with optional expiry. |
| **Key sizes / lifetimes** | RSA key size, SAML cert validity, and assertion window are all **configurable** (`sso.crypto.*`, `sso.saml.*`). |

---

## Sessions & data handling

- **Persistence** — **PostgreSQL 17** via JPA; the full schema (users, roles, permissions,
  MFA factors, OAuth2 clients/authorizations/consents, SAML relying parties, SCIM tokens,
  groups, auth/session policies, signing keys, audit) is managed by **Flyway** migrations.
- **Sessions** — server-side HTTP session keyed by a `JSESSIONID` cookie (HttpOnly, SameSite,
  **Secure in production**). Session state is **single-node in-memory**:
  - a **`SessionRegistry`** tracks live sessions per user for **max-concurrent-session** control
    (oldest evicted when the per-policy cap is exceeded, enforced on every request);
  - a **`SessionMetadataStore`** keeps per-session device info (parsed User-Agent, IP, timestamps)
    behind an **opaque handle** — the real session id is never exposed — powering the
    self-service session list and per-device revoke.
- **Session id rotation** — the id is rotated on authentication and on every step-up
  (`changeSessionId`), and the registry + metadata are re-keyed in lock-step.
- **Audit** — authentication and authorization events (success/failure, identify, admin
  actions, denials) are written to an audit table for observability.

---

## Security hardening

- **Brute-force throttling** — per-IP rate limiting on the auth endpoints (token-bucket); the
  client IP is resolved spoof-safely (dev trusts no `X-Forwarded-For`; prod trusts it **only**
  from a pinned proxy CIDR).
- **IP access lists** — optional allow/deny on the real peer address.
- **Replay protection** — TOTP burns the matched time-step; the enrollment code's own step is
  burned; email OTP enforces an attempt cap + TTL. Code comparisons are **constant-time**.
- **CSRF** — double-submit cookie (`XSRF-TOKEN` readable cookie + `X-XSRF-TOKEN` header) on the
  session-based SPA chain; the stateless protocol/SCIM chains are exempt by design.
- **Zero-Trust session posture** — short idle timeout, absolute session lifetime, optional
  client (User-Agent) binding as defense-in-depth, and re-auth-on-sensitive-action. Sessions are
  **host-bound to their tenant**: a cookie replayed on a different tenant's subdomain is refused.
- **Tenant isolation** — RLS on org-scoped tables (fail-fast if the runtime DB role is a
  superuser), host→tenant→issuer/key derivation, and per-tenant admin-console IP allowlists.
- **Secret hygiene** — all production secrets come from the environment; no defaults for the
  master password or crypto salt in prod (fail-fast); the known-secret demo OIDC client is
  **not seeded in production**.

---

## Federation protocols

| Protocol | Endpoints | Highlights |
|---|---|---|
| **OIDC** | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo` | auth-code + PKCE, client-credentials, refresh, consent, custom claims (profile/email/roles/`org`/`amr`/`acr`/`auth_time`/`stepup_time`/`azp`). **Per-tenant issuer** — discovery/JWKS resolve from the request subdomain. |
| **SAML 2.0** | `/saml2/idp/{metadata,sso}` | `AuthnRequest` over Redirect/POST, MFA-gated, signed `Response`/`Assertion`, per-tenant relying-party registry. |
| **SCIM 2.0** | `/scim/v2/{ServiceProviderConfig,Users,Groups}` | bearer auth, configurable list/filter/bulk limits; a tenant token provisions **into its own org** and sees only its members. |

---

## Tech stack & tools

| Layer | Choice |
|---|---|
| Language / runtime | **Java 21 (LTS)** |
| Framework | **Spring Boot 4.0.x**, **Spring Security 7** (incl. the merged OAuth2 **Authorization Server**) |
| Modularity | **Spring Modulith 2** — enforced module boundaries, verified by `ModularityTests` |
| Multi-tenancy | **PostgreSQL Row-Level Security** (`app.current_org` GUC) + host-derived per-tenant issuer/keys |
| SAML | **OpenSAML 5.1.x** (built directly — Spring has no native SAML *IdP*) |
| SCIM | **scim-sdk 1.33** (framework-agnostic, exposed via a Spring `@RestController`) |
| WebAuthn / Passkeys | **spring-security-webauthn** |
| TOTP / QR | self-contained RFC 6238 + **ZXing** QR rendering |
| Crypto | Spring Security `Encryptors` (AES-256-GCM), JCA RSA, **BouncyCastle** (self-signed X.509) |
| Persistence | **PostgreSQL 17**, JPA/Hibernate, **Flyway** migrations |
| Build | **Gradle** (toolchain-pinned), version catalog |
| Frontend | **React + Vite + TypeScript**, shadcn/ui |
| Dev infra | Docker Compose (PostgreSQL + MailHog), Testcontainers |

---

## Repository layout

```
mini-sso-system/
├── sso-backend/        Spring Boot IdP (Gradle project). Serves the built SPA from
│   ├── src/            its static resources; owns all auth/crypto/protocol logic.
│   ├── data/           runtime SAML keystore (gitignored)
│   └── build.gradle, settings.gradle, gradlew, gradle/
├── sso-frontend/       React admin + login SPA (Vite). Builds INTO sso-backend's
│   └── src/            static resources (single-origin deployable).
├── docker-compose.yml  dev infra: PostgreSQL + MailHog
├── Dockerfile          self-contained multi-stage build (context = repo root):
│                       stage 1 builds the SPA, stage 2 bundles it into the jar.
├── scripts/            Python end-to-end flow checks (OIDC/SAML/admin)
└── test-client/        sample OIDC RP for manual testing
```

---

## Quick start

Prerequisites: JDK 21, Docker, Node 22.

```bash
docker compose up -d                                       # PostgreSQL + MailHog (onboarding/OTT emails)
cd sso-frontend && npm install && npm run build && cd ..   # build SPA into sso-backend static
cd sso-backend && ./gradlew bootRun                        # IdP + SPA at http://localhost:9000
```

Open http://localhost:9000 and sign in as `admin` / `admin123!` (the **platform super-admin**).
First login (for any new user) prompts for the email code (view it in MailHog at
http://localhost:8025) then a strong factor (TOTP via QR or a passkey). For SPA hot-reload during
development: `cd sso-frontend && npm run dev` (Vite on :5173 proxies API/auth to :9000).

**Tenants live on subdomains.** `*.localhost` resolves to `127.0.0.1` on most systems, so a tenant
created with slug `acme` is reachable at `http://acme.localhost:9000`. Create one from the admin
console (Organizations) or via public self-service signup; the activation link lands on the platform
host and, once redeemed, sends the new admin to their own subdomain.

Build the production image (frontend + backend in one shot):

```bash
docker build -t mini-sso .        # multi-stage; context is the repo root
```

### Seeded dev data

| What | Value |
|---|---|
| Platform super-admin (email pre-verified) | `admin` / `admin123!` (`ROLE_ADMIN`; owns the tenant registry, drills into tenants) |
| Global default policies | a global **Default** session policy + auth policy (`org_id IS NULL`) every tenant inherits until it customizes |
| OIDC confidential client (dev only) | `demo-client` / `demo-secret` (auth-code+PKCE, consent) |
| Admin-console OIDC client | `admin-console` (public, PKCE, host-agnostic; powers admin elevation) |
| SCIM bearer token | `dev-scim-token` |
| SAML test SP | entityID `urn:example:sp`, ACS `http://127.0.0.1:8090/acs` |

---

## Configuration

All operational knobs live under `sso.*` (see `application.yml`; prod overrides via env in
`application-prod.yml`). Highlights:

| Area | Keys |
|---|---|
| Issuer / admin seed | `sso.issuer`, `sso.admin.{username,email,password}` |
| Multi-tenancy | `sso.tenancy.{base-domains,require-non-superuser-role}`, `DB_APP_USERNAME`/`DB_APP_PASSWORD` (non-superuser runtime role) |
| Onboarding | `sso.onboarding.{verification-ttl,resend-cooldown,min-password-length,set-password-url,activate-url,workspace-url-template}` |
| Crypto | `sso.crypto.{master-password,salt,rsa-key-size}` |
| Email OTP | `sso.email-otp.{ttl-minutes,max-attempts}` |
| Admin console / elevation | `sso.admin-console.{redirect-uris,access-token-ttl-minutes,refresh-token-ttl-minutes,elevation-freshness-minutes}` |
| Demo client | `sso.demo-client.{enabled,access-token-ttl-minutes,refresh-token-ttl-days}` (disabled in prod) |
| SAML | `sso.saml.{entity-id,sso-location,keystore-*,certificate-dn,key-size,certificate-validity-days,assertion-validity-seconds}` |
| SCIM | `sso.scim.{max-results,max-filter-depth,max-bulk-operations}` |
| Rate limit / lockout | `sso.ratelimit.*`, `sso.lockout.*` |
| Zero-Trust | `sso.zerotrust.{bind-client,session-absolute-lifetime-minutes}` |
| TOTP | `sso.totp.qr-size` |

---

## Key endpoints

| Area | Endpoint |
|---|---|
| Auth (SPA, session) | `/api/auth/{session,organization,identify,login,logout,factors/*,reauth/*,profile,sessions}` |
| Onboarding (public) | `/api/onboarding/{apply,activate,set-password}` (self-service signup → email verification → activation; invitation redemption) |
| OIDC | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo` (per-tenant issuer by host) |
| SAML | `/saml2/idp/{metadata,sso}` |
| SCIM | `/scim/v2/{ServiceProviderConfig,Users,Groups}` (Bearer) |
| Admin (role + permission + elevation, tier-scoped) | `/api/admin/{organizations,users,roles,groups,resources,applications,clients,relying-parties,auth-policies,session-policy,network-zones,portal-settings,audit,scim-tokens,metrics,keys}` — a super-admin adds `X-Org-Context` to drill into a tenant |

---

## Verifying the flows

```bash
cd sso-backend && ./gradlew test        # Testcontainers integration tests (incl. ModularityTests + RLS)
python3 scripts/oidc_authcode_flow.py   # OIDC: MFA session -> PKCE -> ID token
python3 scripts/saml_sso_flow.py        # SAML: MFA-gated SSO -> signed assertion
python3 scripts/admin_api_flow.py       # Admin API: RBAC/PBAC + user lifecycle (session)
python3 scripts/tenant_login_flow.py    # Multi-tenancy: per-tenant login + isolation on a subdomain
python3 scripts/scim_provision_flow.py  # SCIM: provision a user into an org, then log in as them
```

---

## Production

Run with `SPRING_PROFILES_ACTIVE=prod`; secrets come from the environment (see
`application-prod.yml`). Required: `DB_PASSWORD`, `SSO_ISSUER`, `SSO_ADMIN_PASSWORD`,
`SSO_SAML_ENTITY_ID`, `SSO_SAML_SSO_LOCATION`, `SSO_SAML_KEYSTORE_PASSWORD`,
`SSO_CRYPTO_MASTER_PASSWORD`, `SSO_CRYPTO_SALT`, `SSO_ADMIN_CONSOLE_REDIRECT_URIS`. Set
`SSO_TRUSTED_PROXIES` to your load-balancer CIDR so client-IP-based controls are spoof-safe.
Build the image with `docker build -t mini-sso .` (single node; sessions are in-memory — front
with sticky sessions or externalize the session store before scaling out).

**Tenant isolation requires a non-superuser DB role.** PostgreSQL Row-Level Security — the hard
boundary between tenants — is *bypassed by a superuser*, so the application must connect as a
**non-superuser** role. Provision one owning nothing (migration `V54` grants it the DML it needs)
and point the runtime at it: `DB_APP_USERNAME` / `DB_APP_PASSWORD` (the app), while `DB_USERNAME` /
`DB_PASSWORD` stay the schema owner Flyway migrates as. `sso.tenancy.require-non-superuser-role` is
`true`, so startup **fails fast** if the runtime role is a superuser rather than silently running
without isolation. Locally, `docker/postgres-init/10-runtime-role.sql` creates this role (`sso_app`)
automatically.

---

## Conventions

Domain objects expose behavior, not setters; immutable DTOs are `record`s in their own files;
services are thin and depend on abstractions (SOLID). Operational values are externalized to
configuration rather than hardcoded, and fully-qualified Java names are never used inline.
