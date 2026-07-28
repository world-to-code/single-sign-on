# Reviewer agents — purpose index

Each reviewer is a self-contained, **read-only** subagent (it reports findings; it never edits
code). Invoke with a diff range (`review <base>..HEAD`) or explicit files/classes/modules.
This index says **which reviewer engages when**; the authoritative trigger description lives in
each agent's own frontmatter.

## Routing table

| Reviewer | Purpose | Engage when |
|---|---|---|
| [`security-reviewer`](security-reviewer.md) | Adversarial security audit: auth bypass, privesc, injection, crypto, info leaks, side effects. Follows the attack it can construct — depth, not coverage | **End of every plan phase**, and before any commit touching auth, authorization, persistence, crypto, or an external protocol |
| [`owasp-reviewer`](owasp-reviewer.md) | Systematic OWASP Top 10 (2021) sweep — a stated verdict for EVERY category (A01–A10), so a class nobody thought about cannot be skipped; enforces `.claude/rules/backend/owasp.md` | Any diff touching an authz check, query, template, outbound fetch, secret, cookie, dependency or audit line — and always before a release |
| [`zero-trust-reviewer`](zero-trust-reviewer.md) | Zero-trust posture (NIST SP 800-207): implicit trust zones, privilege without expiry, authority read from stale state, revocation that does not end access, self-privilege paths; enforces `.claude/rules/backend/zero-trust.md` | A change that adds or relaxes a trust assumption: internal call paths, cached/frozen authority, revocation flows, re-auth/step-up, async work acting for a user |
| [`protocol-conformance-reviewer`](protocol-conformance-reviewer.md) | Identity-protocol spec conformance: OIDC/OAuth2 (redirect_uri, PKCE, state/nonce, code binding, refresh rotation, id_token claims), SAML (signature-before-use/XSW, InResponseTo, audience, replay), SCIM, WebAuthn ceremonies | Any authorize/token/userinfo/JWKS/discovery, claim set, logout, SAML assertion/metadata, SCIM resource or WebAuthn change — **no other reviewer owns spec text** |
| [`crypto-and-secrets-reviewer`](crypto-and-secrets-reviewer.md) | Key lifecycle (generation/storage/separation/rotation/retirement), primitive use (constant-time, CSPRNG, nonce reuse, alg confusion), secret sprawl into logs/URLs/config/CI | Key material, encryption at rest, hashing, signing, a secret comparison, or anywhere a secret could reach a log, audit row, exception or URL |
| [`input-boundary-reviewer`](input-boundary-reviewer.md) | Untrusted data traced entry → sink: injection (SQL/JPQL/LDAP/OS/SpEL/template), parsers/XXE, deserialization, mass assignment, uploads, path traversal, SSRF, validation PLACEMENT | A new parameter/field, a parser, a query/template/path/URL built from data, request binding, an upload, or a value read out of a remote response |
| [`abuse-and-availability-reviewer`](abuse-and-availability-reviewer.md) | Cost per request and who can schedule it: rate limiting by what an endpoint DOES, lockout, unbounded fan-out, ReDoS, payload bombs, amplification, lock/queue contention | A new endpoint, a loop or guard that fans out per client-supplied element, a regex over user data, an outbound call, an upload, a scheduled/async job |
| [`privacy-pii-reviewer`](privacy-pii-reviewer.md) | Enumeration (response, status, timing), over-disclosure in DTOs/errors, PII in logs/traces/audit/mail/exports, minimization & retention | A new response field, error message, log/audit line, metric or trace attribute, mail/SMS body, export, or search/lookup endpoint |
| [`threat-model-reviewer`](threat-model-reviewer.md) | Design-time STRIDE per trust boundary → the CONTROLS the implementation must contain and the TESTS that must prove them. The only security reviewer that runs **before** the code | Start of a plan phase, a design doc, a new module/integration/actor tier, or any change that introduces or MOVES a trust boundary |
| [`session-security-reviewer`](session-security-reviewer.md) | Session lifecycle & logout propagation: fixation, reuse, sessions outliving logout, Redis/BCL/SLO correctness | Session store, session identity, concurrent-session control, logout/expiry, OIDC BCL, or SAML SLO changes |
| [`tenant-isolation-reviewer`](tenant-isolation-reviewer.md) | Multi-tenancy & tier isolation: cross-tenant read/write, cross-tier (tenant↔platform) leaks, RLS gaps, write-org≠resolve-org mis-filing, per-org-uniqueness collisions, tenant-grantable holes, host/subdomain trust | Org scoping, RLS policies/migrations, OrgContext/drill-in, tenant-grantable perms, per-org uniqueness, host/subdomain derivation, or any query/store/event/audit carrying an `org_id` change |
| [`jpa-reviewer`](jpa-reviewer.md) | Persistence correctness: N+1, lazy-outside-tx (OSIV off), `@ManyToMany`, hidden cascade/dirty-check writes, mapping contradictions, pagination traps, migration drift | Entities, repositories, queries, `@Transactional` boundaries, or Flyway migrations change |
| [`solid-reviewer`](solid-reviewer.md) | SOLID principles (SRP/OCP/LSP/ISP/DIP) + composition-over-inheritance, with the concrete "next change that hurts" named per finding | New classes/abstractions, type-hierarchy growth, type-switching conditionals, structural refactors — typically pre-commit, after security review |
| [`god-class-reviewer`](god-class-reviewer.md) | Whole-class responsibility overload: cohesion clusters, dependency fan-out, change-axis history → verdict + executable decomposition plan | An already-large class grows again, a constructor passes ~5 deps, one class appears in unrelated commits, or a test mocks the world |
| [`module-boundary-reviewer`](module-boundary-reviewer.md) | Spring Modulith boundaries: entity/repository leaks (incl. latent), cross-module write bypasses, event hygiene, cycles, public-surface growth | Any cross-module call, module-root / named-interface change, new event, or entity/repo visibility change |
| [`test-quality-reviewer`](test-quality-reviewer.md) | TDD enforcement: case-matrix completeness, principal-matrix coverage, tx-context honesty, mutation resistance, weakened/flaky tests | Any feature/bugfix diff before commit; urgently when tests changed alongside the code they cover, or a bugfix lands without a regression test |
| [`hygiene-reviewer`](hygiene-reviewer.md) | Mechanical house rules a grep can decide — inline FQNs (**fails CI**), Lombok whitelist / no setters, gratuitous `private static`, magic values, one-public-type-per-file, `.editorconfig` limits | Every diff that touches Java, as the LAST gate before commit — it catches what the Hygiene workflow would otherwise fail on |

## Overlap rules (who owns what)

Each reviewer's own file carries these cross-references inline at the overlapping checklist item.

- **N+1 / lazy loading**: [`jpa-reviewer`](jpa-reviewer.md) owns the full analysis;
  [`security-reviewer`](security-reviewer.md) only flags it when it has availability/security
  weight (its checklist keeps a lightweight item).
- **Module boundaries**: [`module-boundary-reviewer`](module-boundary-reviewer.md) owns
  enforcement; [`security-reviewer`](security-reviewer.md) flags a leak only as part of a security
  path; [`solid-reviewer`](solid-reviewer.md) judges whether the boundary abstraction is
  well-shaped (DIP/ISP), not whether it is crossed.
- **SRP vs god class**: [`solid-reviewer`](solid-reviewer.md) flags responsibility-mixing visible
  in the diff; [`god-class-reviewer`](god-class-reviewer.md) measures the whole class and produces
  the split plan. A solid-review SRP finding on a large class escalates to the god-class reviewer.
- **Sessions vs general security**: anything about a session beginning/living/dying goes to
  [`session-security-reviewer`](session-security-reviewer.md); everything else security-shaped to
  [`security-reviewer`](security-reviewer.md).
- **`security-reviewer` routes, and may dispatch.** It is the DEPTH pass and carries the `Agent` tool:
  its report must end with a mandatory `Lens routing` table naming which specialist lenses the change
  needs, and it may run up to three of them itself as sub-tasks when it has a concrete lead it cannot
  settle on its own axis, folding their findings into its report. Routing is required even when nothing
  is dispatched — an unrouted axis is how a class of bug goes unexamined. Dispatching does not replace
  running a lens deliberately: a lens the invoker runs directly gets the full, unfolded report.
- **Depth vs coverage vs posture vs conformance.** `security-reviewer` hunts the constructible attack;
  `owasp-reviewer` guarantees every vulnerability class got a verdict; `zero-trust-reviewer` asks what
  is still verified once the attacker is inside (a MISSING check, not a broken one);
  `protocol-conformance-reviewer` asks whether the spec clause is actually implemented. Four different
  questions about the same lines.
- **Design before code.** [`threat-model-reviewer`](threat-model-reviewer.md) produces the required
  controls and the test sentence for each; the end-of-phase reviewers verify against them instead of
  re-deriving the requirement. A control nobody required is a control no code review can miss.
- **The three security lenses are complementary, not alternatives.** They differ by METHOD, not by
  topic: [`security-reviewer`](security-reviewer.md) hunts the attack it can construct (depth),
  [`owasp-reviewer`](owasp-reviewer.md) walks all ten categories and must report a verdict for each
  (coverage), [`zero-trust-reviewer`](zero-trust-reviewer.md) asks what remains verified once the
  attacker is already inside (posture — usually a MISSING check rather than a broken one). Running only
  the first reliably produces a report concentrated wherever the change looked weakest. On a sensitive
  change run all three concurrently on the same range and consolidate.
- **Revocation**: [`zero-trust-reviewer`](zero-trust-reviewer.md) decides whether a change SHOULD end
  existing access; [`session-security-reviewer`](session-security-reviewer.md) verifies the termination
  actually lands (Redis, BCL, SLO) and that nothing outlives it.
- **Tenant isolation vs general security**: the cross-tenant / tenant↔platform-tier dimension (org
  scoping, RLS, write-org≠resolve-org, per-org collisions) is owned by
  [`tenant-isolation-reviewer`](tenant-isolation-reviewer.md); [`security-reviewer`](security-reviewer.md)
  flags a crossing it notices but defers the systematic isolation trace there. A single security pass
  reliably MISSES cross-tenant partition/resolution bugs — on any change carrying an `org_id`, run the
  tenant-isolation reviewer as its own lens.
- **Security semantics vs security tests**: the reviewers above judge whether the *code* is
  secure; [`test-quality-reviewer`](test-quality-reviewer.md) judges whether the *tests* would
  catch it becoming insecure (principal-matrix coverage, regression tests for their findings).

## Typical sequencing for a feature

0. Design: [`threat-model-reviewer`](threat-model-reviewer.md) on the plan — it names the trust
   boundaries crossed, the required controls, and the test each control needs.
1. Before implementation (TDD): tests are written first; [`test-quality-reviewer`](test-quality-reviewer.md)
   can vet the case matrix (now including the threat model's test sentences) before any production code
   exists.
2. During/after implementation: [`jpa-reviewer`](jpa-reviewer.md) (if persistence changed),
   [`module-boundary-reviewer`](module-boundary-reviewer.md) (if a boundary changed) — cheap to
   fix while the code is fresh.
3. End of plan phase: [`security-reviewer`](security-reviewer.md) (always),
   [`owasp-reviewer`](owasp-reviewer.md) and [`zero-trust-reviewer`](zero-trust-reviewer.md) (on any
   security-sensitive change — they are the coverage and posture halves the adversarial pass does not
   guarantee), [`session-security-reviewer`](session-security-reviewer.md) and
   [`tenant-isolation-reviewer`](tenant-isolation-reviewer.md) (each if in scope).
4. Pre-commit polish: [`solid-reviewer`](solid-reviewer.md) and
   [`test-quality-reviewer`](test-quality-reviewer.md); escalate to
   [`god-class-reviewer`](god-class-reviewer.md) when a bloated class is flagged.

Run independent reviewers concurrently; give each the same diff range.

## Multi-lens security passes (for sensitive changes)

Experience shows a SINGLE security pass misses class-specific bugs that a lens-focused pass catches. For a
security-sensitive change (auth, authz, audit, tenancy, crypto, an external protocol), run several
**distinct-lens** passes concurrently rather than one generalist review — each with a scope narrowed to
one dimension: authorization/privesc (`security-reviewer`), **OWASP category coverage**
(`owasp-reviewer`), **zero-trust posture** (`zero-trust-reviewer`), **protocol conformance**
(`protocol-conformance-reviewer`), **crypto & secrets** (`crypto-and-secrets-reviewer`), **untrusted
input** (`input-boundary-reviewer`), **abuse & availability** (`abuse-and-availability-reviewer`),
**privacy/enumeration** (`privacy-pii-reviewer`), **tenant/tier isolation**
(`tenant-isolation-reviewer`), and session/auth-flow (`session-security-reviewer`). Pick the lenses whose
surface the diff actually touches — a lens with no surface costs a full agent run and returns nothing. On a recent
audit-authorization change this multi-lens approach surfaced a HIGH (a parallel event-listener bypassing
an enrichment guard) and MEDIUMs (a tenant-login partition mis-file; a sibling endpoint bypassing a PII
gate) that a single pass had cleared. Consolidate all findings by severity, fix, and re-verify before commit.
