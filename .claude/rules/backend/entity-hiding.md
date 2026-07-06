---
paths:
  - "sso-backend/src/main/java/**/*.java"
---

# Never expose a JPA entity or repository across a module

Entities and Spring Data repositories are `internal/domain` implementation detail. They must not
appear in any signature visible to another module — not as return type, parameter, DTO field,
event payload, or generic type argument (`List<AppUser>`, `Optional<...>`, `Page<...>`).

Other modules consume ONLY:

- root read-model interfaces (`UserAccount`, `AuthPolicyView`, …),
- record DTOs in the module root,
- the owning module's service methods (cross-module *writes* go through a behavioral method on
  the owner, never another module's repository, never dirty-checking a fetched object),
- `shared.IdName` for simple id/name lookups.

A public repository method returning an entity is a **latent** leak even while no outside caller
exists — fix the visibility, don't wait for `ModularityTests` to catch it.

Verify after structural changes — the authoritative check is
`./gradlew test --tests ModularityTests`; quick heuristic (each hit's file must live in the SAME
module whose `internal` it imports):

```
rg -n 'import com\.example\.sso\.\w+\.internal\.' sso-backend/src/main/java
```

Related: [module-structure](module-structure.md); reviewer:
`.claude/agents/module-boundary-reviewer.md`.
