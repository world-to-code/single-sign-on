---
paths:
  - "sso-backend/src/main/java/**/internal/domain/**/*.java"
---

# Entity design — shared bases and embeddable value objects

- **Entities extend the `shared.domain` bases:** `AbstractEntity` (UUID id) or `AuditedEntity`
  (id + `created_at`). Never re-declare the id or created-at fields yourself.
- **Group cohesive columns into an `@Embeddable` value object that carries its own behavior** —
  e.g. `AccountLockout` inside `AppUser` owns `recordFailure()`/`isLocked()` instead of the
  entity juggling three loose columns. If a cluster of fields is always read/written together,
  it wants to be an embeddable.
- Entities follow the immutability rules ([immutability](immutability.md)): field access,
  `protected` no-arg constructor for JPA, no setters, intention-revealing mutators.
- Associations follow [lazy-loading](lazy-loading.md); the entity never leaks past the module
  ([entity-hiding](entity-hiding.md)).

Related reviewer: `.claude/agents/jpa-reviewer.md` (mapping contradictions, hidden cascades).
