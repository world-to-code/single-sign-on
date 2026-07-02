# CLAUDE.md — sso-backend

Spring Boot **modular monolith** IdP. Read the root `../CLAUDE.md` first; this file adds backend rules
and OVERRIDES defaults.

## Stack

Java 21 · Spring Boot 4.0.x · Spring Security 7 (OAuth2 Auth Server + WebAuthn) · Spring Modulith 2.0.x ·
OpenSAML 5 · scim-sdk · PostgreSQL + Flyway · Lombok · Gradle. Versions are pinned in `build.gradle`.

Run from `sso-backend/`: `./gradlew compileJava | test | bootRun`
(`SPRING_DEVTOOLS_RESTART_ENABLED=false` when driving live scripts).

## Architecture — modular monolith (NON-NEGOTIABLE)

Each direct sub-package of `com.example.sso` is a `@ApplicationModule` (declared in `package-info.java`).
Keep `ModularityTests` green.

```
<module>/                PUBLIC API only: interfaces + record DTOs + enums/constants
  internal/api/          thin @RestController adapters (no logic)
  internal/application/  service impls (…Impl), view DTOs, factor handlers, seeders, config
  internal/domain/       @Entity + Spring Data repositories
```
Infra modules (`config, security, ratelimit, bootstrap, web, shared`) are not 3-tiered.

- **DIP:** public services are root interfaces; impls are `internal/application/<Name>Impl`. A
  module-private service needs no interface.
- **Thin controllers:** bind HTTP, call ONE service method, shape the response — nothing else (no
  orchestration, try/catch on domain outcomes, mapping, auditing, session work). Extract a service if none fits.
- **DTO placement** (`application` must NEVER depend on `api`): request DTOs with `@Valid` live in
  `internal/api` and self-map to a *public* command via `toSpec()`/`toCommand()`; view/output DTOs live in
  `internal/application`. Exception: genuine app I/O a non-controller consumes (e.g. `FactorVerificationRequest`)
  stays in `application`.
- **No HTTP-status branching on domain results** — services throw `ApiException` subtypes,
  `GlobalExceptionHandler` maps them. Never `ResponseEntity.status(4xx)` for a domain outcome.
- **4+ constructor params → factory/conversion at the layer boundary** (`request.toSpec()`,
  `View.of(domain)`), never `new X(a,b,c,d,…)` at a call site. Genuine multi-source parameter objects
  (`AppAccessQuery`, `AuditRecord`) stay constructors.
- **Never expose a JPA entity/repository across a module.** Other modules consume only root read-model
  interfaces (`UserAccount`, `AuthPolicyView`, …), record DTOs, or service methods; use `shared.IdName`
  for id/name lookups.

## Code style (STRICT)

- **Import — never inline fully-qualified names** (incl. `package-info.java`).
- **No setters** anywhere; mutate via intention-revealing methods + fully-initializing constructors
  (JPA uses field access + a `protected` no-arg ctor).
- **Records for immutables** (DTOs/views/commands); a class only when a record can't express it (entities).
- **Lombok** `@Getter/@RequiredArgsConstructor/@Slf4j/@Builder` — never `@Setter`/`@Data`.
- One public type per file; avoid nested classes.
- **No `private static` unless `static` is required** (`private static final` constants are fine).
- **4xx → shared `ApiException` subtypes** (`NotFound/BadRequest/Conflict/Forbidden/Unauthorized/Locked`
  in `shared.error`); reserve `IllegalState/IllegalArgument` for 500-class invariants.
- **No magic strings/numbers** — protocol values → enum/constant (reuse `OidcScopes`, `HttpMethod`, …);
  tunables → config (below).

## Persistence & config

- **Flyway owns the schema; `ddl-auto=validate`.** Schema change = a new `V<n>__*.sql`; keep physical
  columns identical across mapping refactors (`validate` is the gate).
- **Entities extend `shared.domain` bases:** `AbstractEntity` (UUID id) or `AuditedEntity` (+`created_at`).
  Don't re-declare id/created-at.
- **Group cohesive columns into an `@Embeddable`** value object carrying its own behaviour (e.g.
  `AccountLockout` in `AppUser`).
- **Collections are LAZY — never `EAGER`.** Load with `join fetch` (over `@EntityGraph`); 2+ `Set`s in one
  query is fine, `List` bags are not (MultipleBagFetchException). QueryDSL when dynamic/complex.
- **A detached read must have every needed collection fetch-joined.** An entity read after its tx (cached
  policy, a resolve result read off the request path, or a view projected in a non-`@Transactional` adapter)
  throws `LazyInitializationException`. Fix by fetch-joining, or run load+projection in one tx (make the
  adapter `@Transactional`). Cover adapter projections with an IT that runs OUTSIDE a tx (an ambient tx masks it).
- **Externalize tunables** to `application.yml` + `@Value` — including annotation defaults (make the
  annotation a marker and read the value from config), never a hardcoded number. Protocol constants stay constants.
- **Step-up for sensitive admin actions:** mark destructive/privilege-escalating endpoints (all `*:delete`,
  policy create/update, role/permission grants, group role/manager delegation, key/secret rotation) with
  `@RequireStepUp`; `StepUpInterceptor` enforces `sso.security.step-up.sensitive-max-age` (the stricter of it
  and the session re-auth window) via the `X-Step-Up-Required` 401.
- **Boot 4 splits autoconfig:** a feature needs its `spring-boot-<feature>` module on the classpath (e.g. Flyway).

## Testing

- `./gradlew test` (Testcontainers, needs Docker); context startup runs Hibernate `validate`.
- MockMvc misparses `/oauth2/authorize` & SAML query strings — verify those with `scripts/*.py`.
- After structural changes: `compileJava` + `ModularityTests` + full `test` green; `rg` for zero inline
  FQNs / cross-module entity imports.
