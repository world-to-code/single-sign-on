---
paths:
  - "sso-backend/src/main/java/**/*.java"
  - "sso-backend/src/test/java/**/*.java"
---

# Lazy loading under OSIV-off (correctness rule, not a preference)

`open-in-view` is **false**: any lazy association touched outside an active transaction throws
`LazyInitializationException` at runtime. `@PreAuthorize`/SpEL beans and event listeners run
OUTSIDE the method's transaction.

- **Collections are LAZY — never `EAGER`.** Load what a use case needs with **`join fetch`**
  (preferred over `@EntityGraph`). QueryDSL when the query is dynamic/complex.
- Fetch-joining **2+ `Set` collections in one query is fine; `List` bags are not**
  (`MultipleBagFetchException`) — collection-valued associations are `Set`s.
- **A detached read must have every needed collection fetch-joined.** The known traps: a cached
  policy object, a resolve result read later off the request path, a view projected in a
  non-`@Transactional` adapter. Fix by fetch-joining, or by running load+projection in ONE
  transaction (make the adapter `@Transactional`).
- **Test it honestly:** cover adapter projections with an integration test that runs OUTSIDE a
  transaction — an ambient test tx masks exactly this bug (it has shipped that way once already).
- Collection fetch join + `Pageable` = in-memory pagination (HHH000104) — paginate on a scalar
  query, fetch collections for the page separately. `default_batch_fetch_size: 100` softens N+1
  but per-row queries in a loop are still a bug.

Related: [entity-design](entity-design.md), [flyway](flyway.md); reviewers:
`.claude/agents/jpa-reviewer.md`, `.claude/agents/test-quality-reviewer.md` (tx-context honesty).
