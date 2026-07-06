---
paths:
  - "sso-backend/src/main/resources/db/migration/**"
  - "sso-backend/src/main/java/**/internal/domain/**/*.java"
---

# Flyway owns the schema; `ddl-auto=validate` is the gate

- Every schema change is a new `V<n>__<description>.sql` in `db/migration`. Never edit an applied
  migration; never let Hibernate generate schema.
- `spring.jpa.hibernate.ddl-auto=validate` — context startup (and every test run) fails on
  entity↔schema drift. An entity mapping change without a matching migration (or vice versa) is
  a broken build, so change them **together in one commit**.
- **Mapping refactors keep physical columns identical** (rename in Java via `@Column(name=…)`,
  restructure via `@Embeddable`) — `validate` only checks the physical shape, so keep it stable
  unless the change IS a schema change.
- New migrations: nullability, defaults, `ON DELETE` behavior, unique constraints, and an index
  for every FK and new query predicate — decide each explicitly, don't inherit accidents.

Related: [entity-design](entity-design.md); reviewer: `.claude/agents/jpa-reviewer.md`
(migration-drift).
