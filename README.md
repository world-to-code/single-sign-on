# Mini SSO System — Central Identity Provider

A from-scratch, production-leaning **Single Sign-On Identity Provider (IdP)**. Other
applications delegate authentication to it over **OIDC**, **SAML 2.0**, or **SCIM 2.0**, and
every human sign-in is protected by **policy-driven multi-factor authentication** with a
first-login onboarding flow. Built on **Spring Boot 4 / Spring Security 7**, with a **React**
admin + login console served from the same origin as a single deployable.

> **In one sentence:** log in once, with strong MFA, and get into every connected app — while
> the IdP centrally owns identities, credentials, keys, sessions, and the audit trail.

---

## Table of contents

- [What it does (at a glance)](#what-it-does-at-a-glance)
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
| **OIDC Provider** | OAuth 2.1 + OpenID Connect 1.0 — discovery, JWKS (rotatable RS256), authorization-code + PKCE, client-credentials, refresh tokens, consent, UserInfo. |
| **SAML 2.0 IdP** | OpenSAML 5 — metadata, `AuthnRequest` over HTTP-Redirect/POST, **signed assertions**. |
| **SCIM 2.0 server** | Inbound provisioning of Users/Groups (`/scim/v2`, bearer auth). Provisioned users become real, loginable identities. |
| **Multi-factor auth** | Identifier-first login with **password, TOTP, email OTP, and FIDO2 passkeys**, ordered by a per-user **authentication policy**. |
| **Step-up / elevation** | RFC 9470 fresh re-authentication for sensitive actions; **token-based privilege elevation** to enter the admin console. |
| **RBAC + PBAC** | Roles gate URLs; fine-grained per-user permissions gate operations (`@PreAuthorize`). |
| **Self-service** | "My Profile": registered factors/passkeys, email-verification status, active-session list with per-device revoke. |
| **Admin console** | Same SPA — user lifecycle, OIDC clients, SAML relying parties, groups, session/auth policies, audit log, SCIM tokens, key rotation. |

---

## Architecture

A single deployable. The React SPA is built **into the backend's static resources** and
served from the IdP origin, so it shares the IdP's **session cookie** (no cross-origin token
juggling for the first-party UI). Three isolated `SecurityFilterChain`s separate concerns:
the OAuth2 Authorization Server (protocol endpoints), the SCIM chain (stateless bearer), and
the app/SPA chain (session + CSRF).

```
                                       ┌───────────────────────────────────────────────┐
  OIDC RP  ──/oauth2/*,/.well-known,/userinfo──►│                                       │
  SAML SP  ──/saml2/idp/{metadata,sso}─────────►│   Spring Boot 4 IdP                   │
  Ext IdP  ──/scim/v2/*  (Bearer)──────────────►│   ├─ Spring Security 7 (+ Auth Server)│──JPA──► PostgreSQL 17
  Browser  ──/ (React SPA) + /api/auth/* (cookie)►│   ├─ OpenSAML 5  (SAML IdP)           │         (Flyway-managed)
  Browser  ──/api/admin/* (Bearer elevation)────►│   ├─ scim-sdk    (SCIM 2.0)           │
                                       │   └─ WebAuthn / TOTP / email OTP / RSA keys    │──SMTP──► MailHog (dev)
                                       └───────────────────────────────────────────────┘
```

Browser navigations to `/oauth2/authorize` or `/saml2/idp/sso` that aren't yet
authenticated are redirected to the SPA login, which completes the auth policy and then
**resumes the saved request**.

---

## Authentication — how a user proves who they are

### Identifier-first, policy-driven flow

1. **Identify** — the user submits their email. Accounts are **admin-provisioned
   (invite-only)**: an email with no active account is rejected (this is the tenant-resolution
   point for a future multi-tenant split).
2. **Resolve policy** — `AuthPolicyResolver` picks the authentication policy assigned to that
   user (or the global default). A policy is an **ordered list of required factors**.
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

- **RBAC** — `ROLE_ADMIN` gates the admin URL space at the filter chain.
- **PBAC** — fine-grained per-user permissions (`user:write`, `key:rotate`, …) gate individual
  operations via method-level `@PreAuthorize`. Permissions are assigned **per user**, not via
  roles, so adding a role never silently grants privilege.
- **Admin console = privilege elevation.** Entering `/api/admin/**` requires a **fresh
  bearer access token** obtained through a dedicated first-party `admin-console` OIDC client
  (PKCE). The `AdminElevationFilter` (additive, runs after RBAC/PBAC) accepts the request only
  when the token is:
  - issued by **this IdP** (`iss`) for the **`admin-console`** client (`azp`),
  - carrying the reserved **`admin`** scope and **`roles` ∋ `ROLE_ADMIN`**,
  - asserting **`acr=mfa`** and a **fresh `stepup_time`** (a real step-up, within a configurable window),
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
  client (User-Agent) binding as defense-in-depth, and re-auth-on-sensitive-action.
- **Secret hygiene** — all production secrets come from the environment; no defaults for the
  master password or crypto salt in prod (fail-fast); the known-secret demo OIDC client is
  **not seeded in production**.

---

## Federation protocols

| Protocol | Endpoints | Highlights |
|---|---|---|
| **OIDC** | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo` | auth-code + PKCE, client-credentials, refresh, consent, custom claims (profile/email/roles/`amr`/`acr`/`auth_time`/`stepup_time`/`azp`). |
| **SAML 2.0** | `/saml2/idp/{metadata,sso}` | `AuthnRequest` over Redirect/POST, MFA-gated, signed `Response`/`Assertion`, per-SP relying-party registry. |
| **SCIM 2.0** | `/scim/v2/{ServiceProviderConfig,Users,Groups}` | bearer auth, configurable list/filter/bulk limits, protected role assignment. |

---

## Tech stack & tools

| Layer | Choice |
|---|---|
| Language / runtime | **Java 21 (LTS)** |
| Framework | **Spring Boot 4.0.x**, **Spring Security 7** (incl. the merged OAuth2 **Authorization Server**) |
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

Open http://localhost:9000 and sign in as `admin` / `admin123!`. First login (for any new
user) prompts for the email code (view it in MailHog at http://localhost:8025) then a strong
factor (TOTP via QR or a passkey). For SPA hot-reload during development:
`cd sso-frontend && npm run dev` (Vite on :5173 proxies API/auth to :9000).

Build the production image (frontend + backend in one shot):

```bash
docker build -t mini-sso .        # multi-stage; context is the repo root
```

### Seeded dev data

| What | Value |
|---|---|
| Admin (email pre-verified) | `admin` / `admin123!` |
| OIDC confidential client (dev only) | `demo-client` / `demo-secret` (auth-code+PKCE, consent) |
| Admin-console OIDC client | `admin-console` (public, PKCE; powers admin elevation) |
| SCIM bearer token | `dev-scim-token` |
| SAML test SP | entityID `urn:example:sp`, ACS `http://127.0.0.1:8090/acs` |

---

## Configuration

All operational knobs live under `sso.*` (see `application.yml`; prod overrides via env in
`application-prod.yml`). Highlights:

| Area | Keys |
|---|---|
| Issuer / admin seed | `sso.issuer`, `sso.admin.{username,email,password}` |
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
| Auth (SPA, session) | `/api/auth/{session,identify,login,logout,factors/*,reauth/*,profile,sessions}` |
| OIDC | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo` |
| SAML | `/saml2/idp/{metadata,sso}` |
| SCIM | `/scim/v2/{ServiceProviderConfig,Users,Groups}` (Bearer) |
| Admin (ROLE_ADMIN + permission + elevation) | `/api/admin/{users,roles,clients,saml/relying-parties,groups,audit,scim/tokens,session-policies,auth-policies,keys/rotate}` |

---

## Verifying the flows

```bash
cd sso-backend && ./gradlew test        # Testcontainers integration tests
python3 scripts/oidc_authcode_flow.py   # OIDC: MFA session -> PKCE -> ID token
python3 scripts/saml_sso_flow.py        # SAML: MFA-gated SSO -> signed assertion
python3 scripts/admin_api_flow.py       # Admin API: RBAC/PBAC + user lifecycle (session)
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
