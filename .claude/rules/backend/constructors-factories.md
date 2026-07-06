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

Related: [dto-placement](dto-placement.md), [immutability](immutability.md).
