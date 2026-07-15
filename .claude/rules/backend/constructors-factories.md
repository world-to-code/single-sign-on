---
paths:
  - "sso-backend/src/main/java/**/*.java"
---

# 4+ constructor params → factory/conversion at the layer boundary

Never write `new X(a, b, c, d, …)` at a call site. When a type needs 4 or more constructor
arguments, the conversion happens at the **layer boundary** through a named factory:

- request → command: `request.toSpec()` / `request.toCommand()` (in `internal/api`),
- domain → view: `View.of(domain)` (in `internal/application`).

**Exception:** genuine multi-source parameter objects whose fields come from different callers
(`AppAccessQuery`, `AuditRecord`) keep plain constructors — a factory would just relocate the
argument list.

Why: positional argument lists of 4+ silently survive same-type swaps and force every call site
to change when a field is added; a boundary factory localizes both risks.

## Entities: named factories over a PRIVATE builder/constructor

A JPA entity with many columns is assembled ONLY through **intention-revealing static factories**
on the entity itself (`PolicyBinding.forAttribute(...)`, `Group.create(...)`), never by a caller
running a builder or positional constructor. Business logic (services, writers) and tests both call
the factory — they never touch raw field order.

- Make the all-args constructor **private** and, if Lombok `@Builder` is used, `@Builder(access =
  AccessLevel.PRIVATE)`. The factory bodies use the **name-based builder** internally, so adding or
  reordering a column changes only this one class — not every construction site.
- The JPA `protected` no-arg constructor stays (framework-only).
- A `PolicyBinding.builder()` (or `new PolicyBinding(...)`) appearing OUTSIDE the entity is the smell
  this rule forbids: creation responsibility has leaked into business logic and a column change now
  ripples across call sites.

Why: a public builder/constructor re-exposes the exact positional/field-order fragility the factory
exists to contain, and lets construction invariants (which fields go together) scatter across callers.

Related: [dto-placement](dto-placement.md), [immutability](immutability.md); reviewer:
`.claude/agents/solid-reviewer.md` (creation responsibility / encapsulation).
