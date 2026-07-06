---
paths:
  - "sso-backend/src/main/java/**/*.java"
---

# Services follow DIP: root interface, internal implementation

- A service another module (or a controller in the same module) depends on is declared as an
  **interface in the module root**; its implementation lives at
  `internal/application/<Name>Impl`.
- Consumers depend on the interface, never on the `…Impl` type.
- A **module-private** service (used only inside `internal/`) needs NO interface — do not create
  speculative abstractions for a single internal implementation.

Naming: interface `UserGroupService` → impl `UserGroupServiceImpl`. Keep the pair; do not invent
`Default…`/`…Manager` variants.

Related: [module-structure](module-structure.md); reviewer: `.claude/agents/solid-reviewer.md`
(DIP / speculative-generality).
