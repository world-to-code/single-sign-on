---
paths:
  - "sso-backend/src/test/**"
  - "scripts/**"
---

# Backend testing

- `./gradlew test` runs against **Testcontainers** (Docker required). Context startup runs
  Hibernate `validate`, so entity↔schema drift fails every test run — that's intended
  ([flyway](flyway.md)).
- **MockMvc misparses `/oauth2/authorize` and SAML query strings** — do NOT write MockMvc tests
  for those endpoints; verify them live with `scripts/oidc_authcode_flow.py`,
  `scripts/saml_sso_flow.py`, `scripts/admin_api_flow.py` against `bootRun`.
- **TDD:** write the full case matrix BEFORE implementing (happy, each error path, boundaries,
  principal matrix for authz endpoints), then code to green.
- Adapter/read-model projection tests must run OUTSIDE a transaction — see
  [lazy-loading](lazy-loading.md).
- **After structural changes**, the definition of done is: `./gradlew compileJava` +
  `ModularityTests` + full `./gradlew test` green, plus `rg` sweeps for zero inline FQNs
  ([imports](imports.md)) and zero cross-module entity imports
  ([entity-hiding](entity-hiding.md)).

## Mocks cannot hold invariants the database holds

**Anything enforced by Postgres — a unique constraint, an RLS policy, `ON CONFLICT`, `ON DELETE
CASCADE`, a check constraint — is invisible to a mock.** A service test that stubs the store proves
only "the service called the store"; it cannot prove the store works.

So: **every repository-backed `@Transactional` component needs at least one real-DB integration test
before it is done.** Not the service above it (mocked), not a raw-JDBC RLS probe beside it (bypasses
the component) — the component itself, against a real database, from a non-transactional method.

This has already cost us: a link store's "a concurrent insert is absorbed" was covered only through
mocks and raw JDBC, so nothing ever ran the real insert. It threw `UnexpectedRollbackException` on
the first real-DB test ([db-invariants](db-invariants.md)).

## Know what your tests structurally cannot see

Write the covering test where one is possible; where it is not, say so and move the guarantee
somewhere that can hold it:

- **Concurrency / TOCTOU** — sequential tests are blind by construction. Move the invariant into a DB
  constraint ([db-invariants](db-invariants.md)) instead of "testing harder".
- **Cross-subsystem consequences** — a defect that exists only in the interaction (a login rotating a
  session id and orphaning a logout index) belongs to no unit. Use checklists instead: the
  logout-completeness matrix in `session-security-reviewer`, and the principal matrix below.
- **Missing requirements** — a test encodes what you already thought to require. If nobody asked
  "must revoking this credential end the session?", no test will answer it. That is what the
  adversarial reviewers exist for.

## Mutation-check what you wrote — and know its limit

Before calling a change done, **inject the defect the tests exist to catch, confirm they go red,
revert.** A green suite proves nothing until you have watched it fail. Assert *persisted/observable*
state, not only an accessor's return value — an accessor that re-normalizes hides write-side bugs.

A surviving mutant usually means **duplicated logic**, not merely a missing test: two mechanisms
enforcing one rule, neither individually load-bearing. Remove the redundancy, then one test covers
the one remaining mechanism.

**The limit, stated plainly:** mutation testing perturbs code that EXISTS. A branch you never wrote
cannot be mutated, so the technique measures whether your tests are *real* — never whether they are
*sufficient*. Never report a mutation pass as coverage.

## Principal matrix — for capabilities, not just endpoints

For any new permission, any permission that becomes tenant-grantable, and any new admin surface, the
matrix is not "does the endpoint 401/403 correctly". It is:

> An actor holding ONLY this permission — what is the most privileged thing they can reach,
> including **indirectly**?

Indirect reach is where the damage is: a permission that merely edits *configuration* becomes an
authentication bypass when that configuration decides who may log in as whom. Enumerate the chain,
not the endpoint ([identity-binding](identity-binding.md), [zero-trust](zero-trust.md)).

## Asserting a rejection is not asserting the rule

A test named "rejects a bad host" passes whether or not the scheme is checked. When a validator has
several dimensions (scheme, host, port, redirect-following, size, timeout), assert each separately —
and assert them where the value is USED. Validating stored config proves nothing about a URL later
read out of a discovery/metadata document.

Related reviewer: `.claude/agents/test-quality-reviewer.md`.
