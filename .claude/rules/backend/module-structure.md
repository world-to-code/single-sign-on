---
paths:
  - "sso-backend/src/main/java/**/*.java"
---

# Modular monolith structure (NON-NEGOTIABLE)

Each direct sub-package of `com.example.sso` is a Spring Modulith `@ApplicationModule`, declared in
its `package-info.java`. `ModularityTests` must stay green after every structural change.

Every business module follows the 3-tier layout:

```
<module>/                PUBLIC API only: interfaces + record DTOs + enums/constants
  internal/api/          thin @RestController adapters (no logic)
  internal/application/  service impls (…Impl), view DTOs, factor handlers, seeders, config
  internal/domain/       @Entity + Spring Data repositories
```

- Anything in the module root is public API by definition — adding a type there is an API
  decision, not a convenience.
- Infra modules (`config`, `security`, `ratelimit`, `bootstrap`, `web`, `shared`) are exempt from
  the 3-tier layout but still module-bounded.
- A new top-level package requires a `package-info.java` with the module declaration.

Related: [entity-hiding](entity-hiding.md), [services-dip](services-dip.md),
[dto-placement](dto-placement.md).
