---
paths:
  - "sso-backend/src/main/java/com/example/sso/federation/**/*.java"
  - "sso-backend/src/main/java/com/example/sso/auth/**/*.java"
  - "sso-backend/src/main/java/com/example/sso/saml/**/*.java"
  - "sso-backend/src/main/java/com/example/sso/scim/**/*.java"
  - "sso-backend/src/main/java/com/example/sso/user/**/*.java"
---

# Identity binding — what may be used to decide "this is that account"

This is an IdP: the join between an external assertion and a local account IS the security boundary.
Getting the join key wrong is not a data-modelling mistake, it is an authentication bypass.

## The key must be stable and issued, not descriptive and asserted

- **Never join on email, username, phone, or any other human-readable attribute.** They are mutable
  (a rename silently creates a duplicate account and orphans the original's roles) and re-assignable
  (a recycled corporate address inherits the previous holder's account and privileges).
- **Join on the identifier the protocol guarantees**: OIDC `sub`, SAML `NameID` (persistent), SCIM
  `externalId`. Store the binding explicitly in a link table and resolve through it thereafter.
- **Scope the key by the issuing authority, not by a local label.** An alias/connection name is a
  tenant-chosen label that can be repointed at a different upstream; a link keyed on it silently
  carries over to the new IdP, where a colliding identifier inherits the account. Key on the
  `issuer`. Where the upstream issues *pairwise* identifiers, the client id is part of the namespace
  too — a client-id rotation invalidates every existing subject.
- **Scope the key by tenant.** The same upstream subject may legitimately map to different local
  accounts in different organizations.

## First contact — how a binding is allowed to be created

Resolution order for a federated/external login:

1. the existing link — authoritative;
2. a **directory-issued** identifier already on the account (`externalId` from SCIM/SSO provisioning) — deterministic;
3. just-in-time provisioning of a NEW account, if the connection allows it;
4. refuse.

**Matching an EXISTING local account by verified email is not in that list by default.** If a
deployment needs it, it is an explicit per-connection opt-in that defaults to OFF (this is what
Auth0/Okta do, and Keycloak demands a separate ownership proof). When enabled it may only ever
*create a first* binding — never override or add to an existing one — and it must never be able to
claim an account that holds privileges the asserting party does not already have.

## Bindings are credentials: they must be revocable and recoverable

- A link is a standing authentication credential. **Every link needs an administrative unlink**, or
  a wrong binding is unrecoverable except by direct SQL, and a fail-closed guard becomes a permanent
  account lockout.
- **Revoking a binding must terminate the sessions it authenticated** — see
  [zero-trust](zero-trust.md). Deleting the row while the session lives is not revocation.
- Creating, repointing and revoking a binding are **audited**, with the client IP. The upstream's raw
  subject is not audit material (log a hash or the connection alias).
- Retiring bindings for an upstream must not catch a *different* live connection that happens to
  share that issuer.

## Litmus questions

- If the upstream renames a user's email tomorrow, do they keep their account and roles?
- If the upstream re-assigns a departed employee's address to a new hire, what does the new hire get?
- Who can register a connection, and what is the most privileged account they can then assert?
- After an admin revokes a binding, how long does the attacker's session live?

Related: [zero-trust](zero-trust.md), [owasp](owasp.md) (A01/A07), [db-invariants](db-invariants.md);
reviewers: `.claude/agents/security-reviewer.md`, `.claude/agents/session-security-reviewer.md`.
