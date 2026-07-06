---
paths:
  - "sso-backend/src/main/java/**/*.java"
  - "sso-backend/src/main/resources/application*.yml"
---

# OWASP-aligned coding rules (Top 10 2021 / ASVS, tailored to this IdP)

This system is an Identity Provider: a flaw here compromises every downstream service. These are
coding rules, checked at write time; the adversarial audit lives in
`.claude/agents/security-reviewer.md`, which maps its findings to these same OWASP categories.

- **A01 Broken Access Control — deny by default.** Every `/api/admin/**` handler carries a
  `@PreAuthorize("hasAuthority('<resource>:<action>')")`; the URL gate alone is never enough.
  Mutating endpoints require a mutating permission (never gate a write behind `*:read`).
  Instance-level (ABAC) checks compose with `and`, never `or`. Any object referenced by
  client-supplied id gets an ownership/scope check (no IDOR).
- **A02 Cryptographic Failures.** Secrets/tokens encrypted at rest — never removed in a refactor.
  Compare secrets/tokens constant-time (`MessageDigest.isEqual`). No custom crypto; IDs and
  tokens from `SecureRandom`/UUID, never predictable.
- **A03 Injection.** No string-concatenated JPQL/SQL — bind parameters; whitelist sort columns;
  escape `LIKE` wildcards. XML parsing (SAML, metadata) keeps XXE hardening (no DTDs, no external
  entities). No user input into SpEL.
- **A04 Insecure Design.** Non-revealing errors (no account enumeration by response or timing),
  brute-force lockout, rate limits on auth endpoints — these are load-bearing designs; never
  simplify them away.
- **A05 Security Misconfiguration.** Cookies `HttpOnly` + `Secure` (prod) + explicit `SameSite`;
  CSRF protection on state-changing routes; no debug/actuator exposure in prod profiles.
- **A07 Identification & Authentication Failures.** MFA step ordering is enforced server-side;
  session id rotates on authentication (fixation); TOTP codes are replay-protected; WebAuthn
  validates challenge/origin/RP-ID.
- **A08 Software & Data Integrity Failures.** Validate every inbound token/assertion fully:
  signature, algorithm (reject `none`/alg-confusion), issuer, audience, expiry. SAML responses
  and LogoutRequests require valid signatures before acting.
- **A09 Logging & Monitoring Failures.** Never log secrets, tokens, password material, or bulk
  PII. Admin/security-relevant actions leave an audit trail; failures are logged, not swallowed.
- **A10 SSRF.** Outbound fetches driven by stored/user data (JWKS URIs, SAML metadata,
  back-channel logout URIs, webhooks) validate the target (scheme https, no link-local/metadata
  ranges) and use timeouts.

Related: [zero-trust](zero-trust.md), [error-handling](error-handling.md),
[step-up](step-up.md).
