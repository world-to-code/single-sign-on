---
paths:
  - "sso-backend/src/main/java/**/*.java"
---

# Modular monolith structure (NON-NEGOTIABLE)

Each direct sub-package of `com.example.sso` is a Spring Modulith `@ApplicationModule`, declared in
its `package-info.java`. `ModularityTests` must stay green after every structural change.

Public API is organized into **feature-named `@NamedInterface` slices**; each business module's
internals are regrouped **by feature**, each feature carrying its own 3-tier layout:

```
<module>/
  <feature>/             PUBLIC API slice — package-info carries @NamedInterface("<feature>");
                         interfaces + record DTOs + enums/constants ONLY (never an @Entity)
  internal/
    <feature>/api/         thin @RestController adapters (no logic)
    <feature>/application/ service impls (…Impl), view DTOs, factor handlers, seeders, config
    <feature>/domain/      @Entity + Spring Data repositories
```

- The public surface is the set of `@NamedInterface` slices, NOT a flat module root. Only a
  slice's `package-info` is annotated; a type is public API only by living in an annotated slice —
  adding one is an API decision, not a convenience. Other modules import
  `com.example.sso.<module>.<feature>.<Type>`.
- **Slice by real cohesion, not the plan.** Keep tightly-coupled impls in ONE feature rather than
  splitting them and forcing internal collaborators (helpers, cache events, repositories) public
  just to cross a package line — that trades encapsulation for symmetry. A single cohesive
  aggregate (e.g. `authpolicy`) or an all-to-all resolver cluster (e.g. `user`'s application tier)
  may keep a flat internal even where its public API is sliced; the domain — usually the worst
  bloat — slices freely because its entities are already module-public.
- Infra modules (`config`, `security`, `ratelimit`, `bootstrap`, `web`, `shared`) are exempt from
  both the slicing and the 3-tier layout, but still module-bounded. A self-module-only service
  needs no root interface — keep it in `internal` (e.g. `ratelimit.internal.RateLimiter`).
- A new top-level package requires a `package-info.java` with the `@ApplicationModule` declaration.

Related: [entity-hiding](entity-hiding.md), [services-dip](services-dip.md),
[dto-placement](dto-placement.md).
