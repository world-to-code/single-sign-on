---
paths:
  - "sso-backend/src/main/resources/db/migration/**"
  - "sso-backend/src/main/java/**/internal/domain/**/*.java"
  - "sso-backend/src/main/java/**/internal/application/**/*.java"
---

# Invariants belong in the database, not in a check-then-act

An invariant enforced only by application code is enforced only for one thread at a time. Under two
concurrent requests, "read → decide → write" has no decision at all.

## Uniqueness / at-most-one rules

If the rule is "at most one X per Y" — one link per (account, issuer), one active binding per slot,
one default per org — express it as a **UNIQUE index** (partial/tier-aware where the column is
nullable, see [tier-aware unique constraints]). The application check stays as the friendly error
path; the constraint is what actually holds under load.

A guard written as `if (repository.exists(...)) throw ...;` followed by an insert is a TOCTOU, and no
sequential test can see it. If you write one, the reviewer's question is "which index backs this?"

## Catching a constraint violation does NOT make it recoverable

This one has already shipped here once. Inside an active JPA transaction, a constraint violation
marks the transaction **rollback-only** (Hibernate's `ExceptionConverterImpl`). Swallowing the
`DataIntegrityViolationException` in Java lets the method return normally, and then the *commit*
fails with `UnexpectedRollbackException` from the proxy — after your catch block, where nothing can
handle it. The persistence context is also undefined per spec.

```java
// DON'T — the catch runs, then the commit throws anyway
try { repository.saveAndFlush(row); }
catch (DataIntegrityViolationException alreadyThere) { /* "absorbed" */ }

// DO — let the database resolve the conflict, so nothing is ever thrown
@Modifying
@Query(value = "insert into … values (…) on conflict (…) do nothing", nativeQuery = true)
int insertIfAbsent(…);
```

If you genuinely need to distinguish two constraints on the same statement, inspect the constraint
name and rethrow a domain exception — but do it in a `REQUIRES_NEW` inner transaction, or outside the
transaction entirely.

## Derived deletes are SELECT-then-remove

`deleteByFooAndBar(...)` on a `JpaRepository` issues a SELECT and then one DELETE per row, materializing
every entity. For a bulk retirement use an explicit `@Modifying @Query("delete from …")`, which is one
statement and **returns the affected-row count** — a silent no-op is otherwise indistinguishable from
"there was nothing to delete", which matters when the delete is a security operation.

## Cascades need indexes

Every FK gets an index. A composite index does NOT serve a predicate on a non-leading column, so
`(org_id, issuer, user_id)` does not support `ON DELETE CASCADE` from `app_user (user_id)`. Decide
nullability, `ON DELETE` behaviour, unique constraints and an index for each FK and each new query
predicate explicitly — see [flyway](flyway.md).

Related: [flyway](flyway.md), [entity-design](entity-design.md), [testing](testing.md); reviewer:
`.claude/agents/jpa-reviewer.md`.
