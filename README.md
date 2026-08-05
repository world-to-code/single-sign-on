# Svalinn — Multi-Tenant Identity Provider

*🌏 [한국어 README](README_KR.md)*

A from-scratch, production-leaning **multi-tenant Single Sign-On Identity Provider (IdP)**.
Each **organization is a tenant** with its own subdomain, users, policies, apps, and signing
keys; other applications delegate authentication to it over **OIDC**, **SAML 2.0**, or
**SCIM 2.0**, and every human sign-in is protected by **policy-driven multi-factor
authentication**. It also works in the other direction — a tenant can federate its own login
out to an upstream OIDC or SAML provider. Built as a **Spring Modulith modular monolith** on
**Spring Boot 4 / Spring Security 7**, with a **React** admin + login SPA served by an nginx
edge in front of the API-only backend (one origin to the browser), and **PostgreSQL Row-Level
Security** as the hard boundary between tenants.

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
| **Multi-factor auth** | **Tenant-first**, identifier-first login with **password, TOTP, email OTP, SMS OTP, and FIDO2 passkeys** (incl. per-org passwordless passkey first-factor), ordered by a per-user **authentication policy**. |
| **Inbound federation** | The IdP as **relying party**: a tenant registers upstream **OIDC** or **SAML** providers, and a login there becomes a login here. Links are `(org, issuer, subject)`; unknown users can be **JIT-provisioned**; upstream claims can drive profile attributes. |
| **Single logout** | **OIDC Back-Channel Logout** (RFC-conformant logout tokens, `sid`-keyed) and **SAML SLO**, so ending a session here ends the sessions downstream applications already hold. |
| **Step-up / elevation** | RFC 9470 fresh re-authentication for sensitive actions; **token-based privilege elevation** to enter the admin console, bounded by the acting tenant's session policy. |
| **RBAC + PBAC + DENY** | Roles gate URLs; fine-grained permissions gate operations (`@PreAuthorize`); **wildcard grants** (`user:*`) and explicit **denies** that override any grant, authored per user/role/group/org; instance-level (ABAC) checks scope every object to the acting tenant. |
| **Attribute-based targeting** | Tenant-defined **attributes** on users and groups, with policies and mapping rules targeted by attribute predicates (`EQUALS`, `EXISTS`, `IN`, `CONTAINS`, …, combined with AND). |
| **Per-tenant messaging** | Each tenant can send mail over **its own SMTP relay or an HTTP provider**, from **its own templates** (logic-less rendering, so a template cannot execute anything), and send SMS through a configurable gateway. |
| **Per-tenant branding** | A tenant's login / MFA / consent screens carry its own logo, accent color and product name, resolved own → platform → built-in default. |
| **Self-service** | "My Profile": registered factors/passkeys, email-verification status, active-session list with per-device revoke. |
| **Admin console** | Same SPA — user lifecycle, OIDC clients, SAML relying parties, upstream identity providers, groups, roles, denies, resources, session/auth policies, attributes and mapping rules, network zones, branding, email/SMS settings and templates, audit log, SCIM tokens, key rotation — all tier-scoped to the acting tenant. |

---

## Architecture

The backend is one deployable, structured internally as a **Spring Modulith modular monolith**:
each domain (`user`, `organization`, `authpolicy`, `session`, `oidc`, `saml`, `scim`, `admin`,
`onboarding`, `federation`, `mfa`, `email`, `metadata`, `mapping`, `directory`, `portal`,
`branding`, `resource`, `tenancy`, `audit`, …) is an enforced module exposing only a root API
(interfaces + record DTOs); entities and repositories never cross a module boundary, and
`ModularityTests` keeps the boundaries honest. Modules that must not call each other directly
communicate by **domain event** instead. The React SPA is a **standalone static bundle** served by an **nginx edge** that
reverse-proxies the API/OIDC/SAML paths to the **API-only backend**, so the browser sees ONE origin
and the SPA shares the IdP's **session cookie** (no cross-origin token juggling for the first-party
UI). Isolated `SecurityFilterChain`s separate concerns: the OAuth2 Authorization
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

Each tenant lives at `{slug}.base` (e.g. `acme.localhost`, `acme.idp.example.com`). The host
resolves the org before authentication, so:

- the **OIDC issuer is per-tenant** (`http://acme.localhost` → its own discovery + JWKS),
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
Policy **priority is unique within a tier** (each org's own set + the global set), so the
same-specificity tie-break — and thus the winning policy — is deterministic.
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
| **Email OTP** | 6-digit code mailed to the user (RFC 4648-style). | Configurable TTL + **attempt cap** (code burned after too many guesses); used for first-login email verification. Delivered over the tenant's own relay/provider and template when set. |
| **SMS OTP** | 6-digit code sent to the user's enrolled phone number through a configurable gateway. | Numbers are validated and normalized (libphonenumber) at enrollment; the same TTL/attempt-cap rules as email. An undelivered code is reported as a delivery failure rather than a wrong code. |
| **FIDO2 / Passkeys** | **Spring Security 7 WebAuthn** module — passwordless/possession factor. | Register at login (enroll-at-login) or self-service; counts as a strong/hardware factor. |
| **Federated login** | A completed sign-in at a tenant's upstream OIDC/SAML provider. | Satisfies the **first** factor only — a policy that also requires a second factor still enforces it. |

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

- **`amr`** — methods used: `fed` (upstream federated login), `pwd`, `otp` (authenticator app or emailed
  code), `sms`, `hwk` (passkey), `mfa`.
  A federated session reports `fed` and never `pwd`: the upstream verified the credential, and
  claiming otherwise would tell a relying party this IdP checked a password it never saw.
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
  beyond it. A grant may be a **wildcard** (`user:*`, and `*:*` for the platform super-admin).
- **Role inheritance** — roles form a **DAG**: a role inherits the permissions of the roles below
  it, and an admin can only assign a role their own grants dominate. Inheritance is resolved to
  permission names, never to a role name, so a renamed or re-scoped role cannot smuggle authority.
- **DENY** — an explicit deny overrides any grant, wildcard included, and can be authored against
  a **user, role, group or organization**. Denies are how access is narrowed without dismantling
  a role, and they are subject to the same tier rules: a tenant cannot author or delete a
  platform-level veto, and a deny that would remove the last usable admin is refused (with the
  refusal itself audited).
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
  **Secure in production**), stored in **Redis** via Spring Session
  (`@EnableRedisIndexedHttpSession`). Every node sees every session, so the backend scales
  horizontally with no sticky sessions:
  - the **principal-name index** backs **max-concurrent-session** control (oldest evicted when
    the per-policy cap is exceeded, enforced on every request);
  - per-session device info (parsed User-Agent, IP, timestamps) is a **session attribute** rather
    than a node-local map, exposed behind an **opaque handle** — the real session id never leaves
    the server — powering the self-service session list and per-device revoke. Holding it
    node-locally is what once made "revoke this device" 404 behind a load balancer.
  - a session's Redis key expiring publishes a **`SessionExpiredEvent` with no request attached**,
    which is what lets expiry propagate downstream (requires `notify-keyspace-events Egx`).
- **Session id rotation** — the id is rotated on authentication and on every step-up
  (`changeSessionId`), and the indexes are re-keyed in lock-step.
- **Termination propagates** — disabling, locking or revoking a user's access terminates the live
  sessions rather than waiting for them to expire, and the termination fans out to **OIDC
  back-channel logout** and **SAML SLO**. A **durable retry sweep** backstops deliveries that
  fail, and it audits independently of the store that failed.
- **Audit** — authentication and authorization events (success/failure, identify, admin actions,
  denials, refusals) are written to an audit table, enriched with the acting actor, the client,
  a severity and a reason. A coverage test requires every `/api/admin` write to prove it is
  audited, so a new endpoint cannot quietly land unaudited.

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

### Outbound — this system as the identity provider

| Protocol | Endpoints | Highlights |
|---|---|---|
| **OIDC** | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo` | auth-code + PKCE, client-credentials, refresh, consent, custom claims (profile/email/roles/`org`/`amr`/`acr`/`auth_time`/`stepup_time`/`azp`). **Per-tenant issuer** — discovery/JWKS resolve from the request subdomain. |
| **SAML 2.0** | `/saml2/idp/{metadata,sso,slo}` | `AuthnRequest` over Redirect/POST, MFA-gated, signed `Response`/`Assertion`, per-tenant relying-party registry, **Single Logout**. |
| **SCIM 2.0** | `/scim/v2/{ServiceProviderConfig,Users,Groups}` | bearer auth, configurable list/filter/bulk limits; a tenant token provisions **into its own org** and sees only its members. |

### Inbound — this system as the relying party

A tenant registers upstream providers under an **alias** and its users sign in there instead of
(or as well as) here. Both protocols share one registry, one link model and one JIT path.

| Direction | Endpoints | Highlights |
|---|---|---|
| **Inbound OIDC** | `/api/auth/federation/{alias}/{start,callback}` | Discovery-driven; the upstream `(issuer, sub)` is the identity, never the email. |
| **Inbound SAML** | `/api/auth/federation/{alias}/acs`, SP metadata under `/api/admin/identity-providers/{alias}/saml/metadata` | The ACS POST arrives **without the session cookie** (`SameSite=Lax`), so the login org is an explicit input and a separate `SameSite=None` cookie binds the browser. |

- **Linking** — a link is `(org, upstream issuer, upstream subject)` in a qualified namespace, so
  a SAML `acme` and an OIDC `acme` can never collide. Links are revocable credentials: removing
  one ends the sessions it authenticated.
- **JIT provisioning** — an unknown user can be created on first federated login. Matching an
  **existing** account by email requires the upstream to prove the address is verified;
  provisioning a new one needs only a name, which is the asymmetry that keeps an unverified
  upstream from taking over an existing account.
- **Attribute sourcing** — upstream claims can populate profile attributes, configured per
  provider and per protocol.

### Logout

**OIDC Back-Channel Logout** delivers `sid`-keyed logout tokens to participating clients, and
**SAML SLO** covers SAML SPs. Both are driven from session termination, so an administrator
disabling an account ends the downstream sessions too — a revoked credential that leaves live
sessions behind has not actually revoked anything.

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
| Sessions / cache | **Redis** + **Spring Session** (indexed, keyspace-notified), **Caffeine** in-process cache |
| Rate limiting / resilience | **Bucket4j** (Redis-backed token buckets), **resilience4j** |
| Templating | **jmustache** — logic-less, so a tenant-authored email template cannot execute anything |
| Directory / import | **UnboundID LDAP SDK**, **commons-csv**, **libphonenumber** (phone normalization) |
| Observability | **Micrometer** + **Prometheus** registry, **OpenTelemetry** tracing bridge |
| Build | **Gradle** (toolchain-pinned), version catalog |
| Frontend | **React + Vite + TypeScript**, shadcn/ui, **vitest** |
| Dev infra | Docker Compose (PostgreSQL + Redis + MailHog), Testcontainers |

---

## Repository layout

```
mini-sso-system/
├── sso-backend/        Spring Boot IdP (Gradle project). API-only; owns all auth/crypto/
│   ├── src/            protocol logic. Dockerfile = the API image (no SPA bundled).
│   ├── data/           runtime SAML keystore (gitignored)
│   └── build.gradle, settings.gradle, gradlew, gradle/
├── sso-frontend/       React admin + login SPA (Vite). Builds a standalone dist/ bundle;
│   ├── src/            Dockerfile = the nginx EDGE (serves dist/ + reverse-proxies the API).
│   └── nginx/          edge config (SPA fallback + backend proxy; mirrors the vite dev proxy)
├── docker-compose.yml           dev infra: PostgreSQL + Redis + MailHog
├── docker-compose.testinfra.yml ONE PostgreSQL + Redis for the whole test suite (non-dev ports)
├── docker-compose.prod.yml      full split stack (edge + API backend + datastores) for local prod-topology
├── .github/workflows/  CI: an orchestrator fanning out to one reusable workflow per role
├── docs/               project docs (commit convention, design notes)
├── scripts/            Python end-to-end flow checks (OIDC/SAML/SCIM/admin/logout)
└── test-client/        sample OIDC RP for manual testing
```

---

## Quick start

Prerequisites: JDK 21, Docker, Node 22.

```bash
docker compose up -d                              # dev infra: PostgreSQL + Redis + MailHog
cd sso-backend && ./gradlew bootRun               # API-only backend at http://localhost:9000
# in another shell — the SPA dev server (Vite proxies API/auth/OIDC/SAML to :9000):
cd sso-frontend && npm install && npm run dev     # http://localhost:5173
```

Open http://localhost:5173 and sign in as `admin` / `admin123!` (the **platform super-admin**).
First login (for any new user) prompts for the email code (view it in MailHog at
http://localhost:8025) then a strong factor (TOTP via QR or a passkey).

Run the **split deployment locally** (nginx edge + API-only backend, the production topology):

```bash
docker compose -f docker-compose.prod.yml up --build    # single public origin at http://localhost
```

**Tenants live on subdomains.** `*.localhost` resolves to `127.0.0.1` on most systems, so with the
split stack a tenant created with slug `acme` is reachable at `http://acme.localhost` — the edge
forwards the full `Host` so the backend derives the tenant and its per-tenant issuer. (The Vite dev
server rewrites the host to the platform origin, so exercise subdomain tenancy against the edge.)
Create a tenant from the admin console (Organizations) or via public self-service signup; the
activation link lands on the platform host and, once redeemed, sends the new admin to their subdomain.

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
| SMS | `sso.sms.*` — outbound gateways; per-tenant credentials live in `sms_settings`, not here |
| Deployment plane | `sso.plane` (`all` in dev; the API-only backend behind the edge in the split topology) |
| Admin console / elevation | `sso.admin-console.{redirect-uris,access-token-ttl-minutes,refresh-token-ttl-minutes,elevation-freshness-minutes}` |
| Demo client | `sso.demo-client.{enabled,access-token-ttl-minutes,refresh-token-ttl-days}` (disabled in prod) |
| SAML | `sso.saml.{entity-id,keystore-*,certificate-dn,key-size,certificate-validity-days,assertion-validity-seconds}` |
| SCIM | `sso.scim.{max-results,max-filter-depth,max-bulk-operations}` |
| Rate limit / lockout | `sso.ratelimit.*`, `sso.lockout.*` |
| Zero-Trust | `sso.zerotrust.{bind-client,session-absolute-lifetime-minutes}` |
| TOTP | `sso.totp.qr-size` |

---

## Key endpoints

| Area | Endpoint |
|---|---|
| Auth (SPA, session) | `/api/auth/{session,organization,identify,login,logout,factors/*,reauth/*,profile,sessions,branding}` |
| Inbound federation | `/api/auth/federation/{alias}/{start,callback,acs}` |
| Consent (SPA) | `/consent` renders it; `/api/oauth2/consent` serves the model, and approval posts back to `/oauth2/authorize` |
| Onboarding (public) | `/api/onboarding/{apply,activate,set-password}` (self-service signup → email verification → activation; invitation redemption) |
| User portal | `/api/portal/*` (assigned applications, self-service) |
| OIDC | `/.well-known/openid-configuration`, `/oauth2/{authorize,token,jwks}`, `/userinfo` (per-tenant issuer by host) |
| SAML | `/saml2/idp/{metadata,sso,slo}` |
| SCIM | `/scim/v2/{ServiceProviderConfig,Users,Groups}` (Bearer) |
| Admin (role + permission + elevation, tier-scoped) | `/api/admin/{organizations,users,groups,denies,applications,clients,saml/relying-parties,identity-providers,session-policies,network-zones,portal-settings,audit,scim/tokens,metrics,branding,email-templates,smtp-settings,sms-settings,attribute-definitions,profiles,mapping-rules,metadata}` — a super-admin adds `X-Org-Context` to drill into a tenant |

---

## Verifying the flows

```bash
docker compose -f docker-compose.testinfra.yml up -d   # optional but preferred — see below
cd sso-backend && ./gradlew test        # integration tests (incl. ModularityTests + RLS)
cd sso-frontend && npm test             # vitest (jsdom) — and npm run build for the type check
```

The suite forks across JVMs and a Testcontainers singleton is per-JVM, so without the shared
test infra every fork starts its own PostgreSQL, Redis and reaper. Each fork still gets its own
database inside that shared server, so isolation is unchanged. Falling back to Testcontainers is
what CI does.

Protocol flows are exercised against a running server, because MockMvc cannot carry a real
OIDC/SAML round trip:

```bash
python3 scripts/oidc_authcode_flow.py      # OIDC: MFA session -> PKCE -> ID token
python3 scripts/saml_sso_flow.py           # SAML: MFA-gated SSO -> signed assertion
python3 scripts/saml_slo_flow.py           # SAML: Single Logout
python3 scripts/saml_inbound_flow.py       # Inbound SAML: upstream login -> local session
python3 scripts/backchannel_logout_flow.py # OIDC: termination -> logout token delivery
python3 scripts/admin_api_flow.py          # Admin API: RBAC/PBAC + user lifecycle (session)
python3 scripts/tenant_login_flow.py       # Multi-tenancy: per-tenant login + isolation on a subdomain
python3 scripts/scim_provision_flow.py     # SCIM: provision a user into an org, then log in as them
python3 scripts/network_zone_flow.py       # Network zones: IP-conditioned policy
```

---

## Production

Run with `SPRING_PROFILES_ACTIVE=prod`; secrets come from the environment (see
`application-prod.yml`). Required: `DB_PASSWORD`, `REDIS_PASSWORD`, `SSO_ISSUER`,
`SSO_ADMIN_PASSWORD`, `SSO_SAML_ENTITY_ID`, `SSO_SAML_KEYSTORE_PASSWORD`,
`SSO_CRYPTO_MASTER_PASSWORD`, `SSO_CRYPTO_SALT`, `SSO_ADMIN_CONSOLE_REDIRECT_URIS`. Set
`SSO_TRUSTED_PROXIES` to the edge/load-balancer CIDR so client-IP-based controls are spoof-safe, and
`SSO_ISSUER` to the **public edge origin** (the host the browser uses).

**Split deployment.** Build the two images from their per-service Dockerfiles — the API backend
(`docker build -t mini-sso-backend sso-backend/`) and the nginx edge that serves the SPA and
reverse-proxies the backend (`docker build -t mini-sso-frontend sso-frontend/`); `docker-compose.prod.yml`
wires the full stack. Both runtime images are **Alpine + non-root**. Sessions live in **Redis**, so the
backend scales horizontally behind the edge (no sticky sessions needed); revocation propagates across
nodes via Redis keyspace events.

**Secrets (SOPS).** `docker-compose.prod.yml` takes every secret as a **required** env var (`${VAR:?…}`) — it
refuses to boot on a weak baked-in default (no `admin123!` fail-open). Supply them from a
[SOPS](https://github.com/getsops/sops) + [age](https://github.com/FiloSottile/age)-encrypted file whose
**ciphertext is safe to commit** (only the secret *values* are encrypted; the private key is never committed).
One-time setup:

```bash
age-keygen -o age.key                                   # your PRIVATE key — gitignored, never commit
age-keygen -y age.key                                   # paste the age1… public key into .sops.yaml
cp secrets/prod.example.yaml secrets/prod.sops.yaml     # fill in real values
sops --encrypt --in-place secrets/prod.sops.yaml        # now safe to commit
```

Then run the stack with the secrets decrypted into the process environment (never written to disk), and
decrypt per context — the private key lives outside git in all three:

- **Local / compose** — `SOPS_AGE_KEY_FILE=age.key sops exec-env secrets/prod.sops.yaml 'docker compose -f docker-compose.prod.yml up --build'`.
- **Kubernetes** — store the age key as a cluster secret and let a controller decrypt at apply time: Flux's
  built-in SOPS (`spec.decryption.provider: sops` + a `sops-age` secret), the sops-secrets-operator, or a
  `sops -d … | kubectl apply -f -` step in CD with the key as a pipeline secret.
- **CI** — needs no key: the `secrets` job fails if any committed `secrets/*.yaml` (bar the example) is *not*
  SOPS-encrypted, and proves the repo's `.sops.yaml` rules encrypt correctly via an ephemeral-key round-trip.

## CI

`.github/workflows/ci.yml` runs on every push/PR as an **orchestrator**: it fans out to one reusable
workflow per role, each in its own file, with `contents: read` as the ceiling (a reusable workflow cannot
exceed its caller's grants) and one in-flight run per ref.

| Workflow | Checks |
|---|---|
| `backend.yml` | Gradle + Testcontainers, then the API image — built only after the tests pass |
| `frontend.yml` | vitest, `tsc` + vite build, then the nginx edge image |
| `config.yml` | compose files + `nginx -t` |
| `security.yml` | SOPS verification (no plaintext secret committed, the example holds only placeholders, the `.sops.yaml` rules round-trip), **Trivy** secret scan, and `npm audit --audit-level=high` |
| `hygiene.yml` | the mechanical house rules, chiefly no inline fully-qualified Java names |

Images are **built, never pushed** — this is CI, not CD, and a container is never produced from code that
has not passed its own tests.

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
