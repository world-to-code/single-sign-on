---
paths:
  - "sso-backend/src/main/java/**/internal/api/**/*.java"
  - "sso-backend/src/main/java/**/internal/application/**/*.java"
---

# DTO placement — `application` must NEVER depend on `api`

Dependency direction inside a module is `api → application → domain`. DTO homes follow it:

- **Request DTOs** (with `@Valid` bean-validation annotations) live in `internal/api` and
  self-map to a *public* command/spec via `toSpec()` / `toCommand()` (e.g.
  `resource/internal/api/ResourceRequest`). The service signature takes the public command,
  never the request DTO.
- A request record that **another module binds** is public API and therefore lives in the module
  **root** (e.g. `user.GroupRequest`, bound by the admin module's `AdminGroupController`) — it
  still self-maps (`GroupRequest.toSpec()` → `GroupSpec`).
- **View / output DTOs** live in `internal/application` (or the module root when another module
  consumes them). Build them via a static factory: `View.of(domain)`.
- **Exception:** genuine application I/O that a non-controller also consumes (e.g.
  `FactorVerificationRequest`) stays in `application`.

Litmus test: if a class in `internal/application` imports anything from `internal/api`, the
placement is wrong — move the type or introduce a public command.

```java
// DO: request self-maps to a public command (real example: user.GroupRequest)
public record GroupRequest(@NotBlank String name, String description, ...) {
    public GroupSpec toSpec() { ... }
}
```

Related: [thin-controllers](thin-controllers.md),
[constructors-factories](constructors-factories.md).
