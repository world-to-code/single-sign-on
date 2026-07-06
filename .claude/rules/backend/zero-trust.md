---
paths:
  - "sso-backend/src/main/java/**/*.java"
  - "sso-backend/src/main/resources/application*.yml"
---

# Zero-trust rules — never trust, always verify

Principles (NIST SP 800-207) applied as concrete coding rules for this IdP:

- **Verify explicitly, per request.** No code path assumes "an upstream filter already checked."
  Method security (`@PreAuthorize`) is the authorization point of record — a handler reachable
  only through a gateway/URL rule still carries its own check. Every token/assertion is fully
  validated (signature, issuer, audience, expiry) at each use, not only at issuance.
- **No implicit trust zones.** Localhost, internal network, "called from another module", or
  "only our SPA calls this" never justify a weaker check. Cross-module calls still go through the
  owning module's behavioral API ([entity-hiding](entity-hiding.md)); SCIM and other machine
  clients authenticate on every request.
- **Least privilege.** Grant the minimal permission an operation needs; permission implication
  only expands downward (a mutating `resource:action` implies `resource:read`, never the
  reverse). New roles/permissions default to nothing. Self-privilege operations (editing your own
  roles/permissions/lockout) are denied by policy, not by hoping the UI hides the button.
- **Assume breach — limit blast radius and lifetime.** Privilege is time-boxed: admin elevation
  and step-up expire (`@RequireStepUp`, [step-up](step-up.md)); sessions have an absolute
  lifetime and are bound to the establishing client (`sso.zerotrust.*` in `application.yml` —
  extend that namespace for new zero-trust tunables); sessions are revocable and revocation
  PROPAGATES (Redis termination → OIDC back-channel logout / SAML SLO). Access changes (disable,
  lock, role revoke) take effect by terminating live sessions, not by waiting for expiry.
  Secrets are encrypted at rest; the session store (Redis) is treated as an auth-critical secret
  store (mandatory password in prod).
- **Re-verify state on sensitive operations.** Before a destructive/privilege-changing action,
  check current account state (enabled, not locked) and freshness (step-up) — do not trust
  authorities frozen into a session serialized minutes or days ago.
- **Continuous monitoring.** Security-relevant decisions (denials, lockouts, terminations,
  elevation) are observable: logged/audited so a breach is detectable, without leaking secrets
  ([owasp](owasp.md) A09).

Litmus question for any new code path: *"if an attacker already holds a foothold behind this
point (a cookie, an internal network position, a low-privilege account), what does this code
still verify?"* The answer must never be "nothing".

Related: [owasp](owasp.md), [step-up](step-up.md), [config-tunables](config-tunables.md);
reviewers: `.claude/agents/security-reviewer.md`,
`.claude/agents/session-security-reviewer.md`.
