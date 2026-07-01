# CLAUDE.md — sso-backend

Spring Boot **modular monolith** IdP. Read the root `../CLAUDE.md` first (git, security, general
engineering). This file covers backend-specific architecture and rules; they OVERRIDE defaults.

## Stack

Java 21 · Spring Boot 4.0.x · Spring Security 7 (OAuth2 Authorization Server + WebAuthn) ·
Spring Modulith 2.0.x · OpenSAML 5 · scim-sdk · PostgreSQL + Flyway · Lombok · Gradle.
Versions are pinned in `build.gradle` — check it, don't guess.

Run from `sso-backend/`: `./gradlew compileJava` · `./gradlew test` · `./gradlew bootRun`
(`SPRING_DEVTOOLS_RESTART_ENABLED=false` when driving live scripts).

## Architecture — modular monolith (NON-NEGOTIABLE)

Each direct sub-package of `com.example.sso` is a Spring Modulith `@ApplicationModule`
(declared in `package-info.java`). `ModularityTests` fails the build on any illegal cross-module
access or cycle — **keep it green**.

Per feature module:
```
<module>/                 ← PUBLIC API only: interfaces + DTOs/records + enums/constants
  package-info.java        @ApplicationModule
  internal/
    api/                   presentation — @RestController/@Controller + controller-only HTTP DTOs
    application/           business — service impls (…Impl), factor handlers, seeders, config
    domain/                persistence — @Entity + Spring Data repositories
```
Infra modules (`config, security, ratelimit, bootstrap, web, shared`) are NOT 3-tiered.

Rules:
- **Public services are interfaces in the module root; impls are `internal/application/<Name>Impl`
  (DIP).** Module-private services just live in `internal/application` (no interface).
- **Never expose a JPA entity (or repository) across a module boundary.** Entities live in
  `internal/domain`. Other modules consume ONLY: root **read-model interfaces** (e.g. `UserAccount`,
  `RoleRef`, `AuthPolicyView`, `SessionPolicyDetails`), **record DTOs** (e.g. `AuditEntry`,
  `GroupView`, `AdminPortalSettingsData`), or the owning module's **service methods**. Cross-module
  writes go through behavioral service methods — never by handing out an entity. Prefer the
  `shared.IdName` projection for id/name lookups.

## Code style (STRICT — enforced on review)

- **Never write a fully-qualified name inline — always `import`.** This includes `package-info.java`
  annotations (`@ApplicationModule` + the import AFTER the `package` line, Spring-style).
- **Never use setters** (`setX`), not on entities, not on DTOs. Mutate via intention-revealing
  domain methods (`enable()`, `changePassword(...)`, `assignRoles(...)`) and fully-initializing
  constructors; JPA uses field access + a `protected` no-arg ctor.
- **Records for immutables** (DTOs, views, commands, results) — records are the default; use a class
  only when a record genuinely can't express it (e.g. JPA entities).
- **Use Lombok maximally** — `@Getter`, `@RequiredArgsConstructor`, `@Slf4j`, `@Builder` — but
  **never `@Setter` or `@Data`** (Data pulls in setters).
- One public type per file; avoid nested/inner classes unless truly necessary.

## Persistence & config

- **Flyway owns the schema; `spring.jpa.hibernate.ddl-auto=validate`.** Any schema change =
  a new `V<n>__*.sql` migration; entities map to it (subset mappings are fine for validate).
- **Externalize tunables** — no hardcoded timeouts/sizes/URLs; use `application.yml` + `@Value`.
  Genuine protocol constants (algorithm names, claim keys, `ROLE_`/`FACTOR_` strings) stay as constants.
- Spring Boot 4 splits autoconfig: a feature integration needs its explicit `spring-boot-<feature>`
  module on the classpath (e.g. Flyway). If autoconfig "silently" doesn't run, check for the module.

## Testing / verifying

- Unit + Testcontainers ITs via `./gradlew test` (needs Docker). Context startup runs Hibernate
  `validate`, so entity-mapping mistakes fail tests.
- MockMvc misparses `/oauth2/authorize` & SAML query strings — verify those flows with the live
  `scripts/*.py`, not MockMvc.
- After any structural change: `compileJava` + `ModularityTests` + full `test` green, and
  `rg` for zero inline FQNs / zero cross-module entity imports.
