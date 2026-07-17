# Reviewer agents — purpose index

Each reviewer is a self-contained, **read-only** subagent (it reports findings; it never edits
code). Invoke with a diff range (`review <base>..HEAD`) or explicit files/classes/modules.
This index says **which reviewer engages when**; the authoritative trigger description lives in
each agent's own frontmatter.

## Routing table

| Reviewer | Purpose | Engage when |
|---|---|---|
| [`security-reviewer`](security-reviewer.md) | Adversarial security audit: auth bypass, privesc, injection, crypto, info leaks, zero-trust regressions — findings mapped to OWASP Top 10; enforces `.claude/rules/backend/owasp.md` + `zero-trust.md` | **End of every plan phase**, and before any commit touching auth, authorization, persistence, crypto, or an external protocol |
| [`session-security-reviewer`](session-security-reviewer.md) | Session lifecycle & logout propagation: fixation, reuse, sessions outliving logout, Redis/BCL/SLO correctness | Session store, session identity, concurrent-session control, logout/expiry, OIDC BCL, or SAML SLO changes |
| [`tenant-isolation-reviewer`](tenant-isolation-reviewer.md) | Multi-tenancy & tier isolation: cross-tenant read/write, cross-tier (tenant↔platform) leaks, RLS gaps, write-org≠resolve-org mis-filing, per-org-uniqueness collisions, tenant-grantable holes, host/subdomain trust | Org scoping, RLS policies/migrations, OrgContext/drill-in, tenant-grantable perms, per-org uniqueness, host/subdomain derivation, or any query/store/event/audit carrying an `org_id` change |
| [`jpa-reviewer`](jpa-reviewer.md) | Persistence correctness: N+1, lazy-outside-tx (OSIV off), `@ManyToMany`, hidden cascade/dirty-check writes, mapping contradictions, pagination traps, migration drift | Entities, repositories, queries, `@Transactional` boundaries, or Flyway migrations change |
| [`solid-reviewer`](solid-reviewer.md) | SOLID principles (SRP/OCP/LSP/ISP/DIP) + composition-over-inheritance, with the concrete "next change that hurts" named per finding | New classes/abstractions, type-hierarchy growth, type-switching conditionals, structural refactors — typically pre-commit, after security review |
| [`god-class-reviewer`](god-class-reviewer.md) | Whole-class responsibility overload: cohesion clusters, dependency fan-out, change-axis history → verdict + executable decomposition plan | An already-large class grows again, a constructor passes ~5 deps, one class appears in unrelated commits, or a test mocks the world |
| [`module-boundary-reviewer`](module-boundary-reviewer.md) | Spring Modulith boundaries: entity/repository leaks (incl. latent), cross-module write bypasses, event hygiene, cycles, public-surface growth | Any cross-module call, module-root / named-interface change, new event, or entity/repo visibility change |
| [`test-quality-reviewer`](test-quality-reviewer.md) | TDD enforcement: case-matrix completeness, principal-matrix coverage, tx-context honesty, mutation resistance, weakened/flaky tests | Any feature/bugfix diff before commit; urgently when tests changed alongside the code they cover, or a bugfix lands without a regression test |

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

1. Before implementation (TDD): tests are written first; [`test-quality-reviewer`](test-quality-reviewer.md)
   can vet the case matrix before any production code exists.
2. During/after implementation: [`jpa-reviewer`](jpa-reviewer.md) (if persistence changed),
   [`module-boundary-reviewer`](module-boundary-reviewer.md) (if a boundary changed) — cheap to
   fix while the code is fresh.
3. End of plan phase: [`security-reviewer`](security-reviewer.md) (always),
   [`session-security-reviewer`](session-security-reviewer.md) and
   [`tenant-isolation-reviewer`](tenant-isolation-reviewer.md) (each if in scope).
4. Pre-commit polish: [`solid-reviewer`](solid-reviewer.md) and
   [`test-quality-reviewer`](test-quality-reviewer.md); escalate to
   [`god-class-reviewer`](god-class-reviewer.md) when a bloated class is flagged.

Run independent reviewers concurrently; give each the same diff range.

## Multi-lens security passes (for sensitive changes)

Experience shows a SINGLE security pass misses class-specific bugs that a lens-focused pass catches. For a
security-sensitive change (auth, authz, audit, tenancy, crypto, an external protocol), run several
**distinct-lens** passes concurrently rather than one generalist review — each with a scope narrowed to
one dimension: authorization/privesc, **tenant/tier isolation** (`tenant-isolation-reviewer`), enumeration
& PII/info-leak, session/auth-flow (`session-security-reviewer`), and injection/DoS/logic. On a recent
audit-authorization change this multi-lens approach surfaced a HIGH (a parallel event-listener bypassing
an enrichment guard) and MEDIUMs (a tenant-login partition mis-file; a sibling endpoint bypassing a PII
gate) that a single pass had cleared. Consolidate all findings by severity, fix, and re-verify before commit.
