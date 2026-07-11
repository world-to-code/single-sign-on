# CLAUDE.md — sso-backend

Spring Boot **modular monolith** IdP. Read the root `../CLAUDE.md` first; this file adds backend rules
and OVERRIDES defaults.

Detailed backend rules are managed one-per-topic in **`../.claude/rules/backend/`** (path-scoped:
each loads automatically when a matching file enters context). This file keeps the stack, the
layout, and a one-line index — **the rule file is the authoritative, full statement**; do not
restate rule details here.

## Stack

Java 21 · Spring Boot 4.0.x · Spring Security 7 (OAuth2 Auth Server + WebAuthn) · Spring Modulith 2.0.x ·
OpenSAML 5 · scim-sdk · PostgreSQL + Flyway · Lombok · Gradle. Versions are pinned in `build.gradle`.

Run from `sso-backend/`: `./gradlew compileJava | test | bootRun`.

## Architecture sketch (NON-NEGOTIABLE — details in rules)

Each direct sub-package of `com.example.sso` is a `@ApplicationModule`; keep `ModularityTests` green.

```
<module>/                PUBLIC API only: interfaces + record DTOs + enums/constants
  internal/api/          thin @RestController adapters (no logic)
  internal/application/  service impls (…Impl), view DTOs, factor handlers, seeders, config
  internal/domain/       @Entity + Spring Data repositories
```
Infra modules (`config, security, ratelimit, bootstrap, web, shared`) are not 3-tiered.

## Rule index (`../.claude/rules/backend/`)

Architecture:
- `module-structure.md` — module/3-tier layout, `package-info.java`, ModularityTests
- `entity-hiding.md` — never expose a JPA entity/repository across a module
- `services-dip.md` — public service = root interface + `internal/application/<Name>Impl`
- `thin-controllers.md` — bind → ONE service call → shape response; nothing else
- `dto-placement.md` — request DTOs in `api` (`toSpec()`/`toCommand()`); `application` never depends on `api`
- `error-handling.md` — `ApiException` subtypes + `GlobalExceptionHandler`; no `ResponseEntity.status(4xx)`
- `constructors-factories.md` — 4+ ctor params → factory at the layer boundary

Code style:
- `imports.md` — never inline fully-qualified names
- `immutability.md` — no setters; records for immutables; Lombok whitelist (never `@Setter`/`@Data`)
- `file-layout.md` — one public type per file; no gratuitous `private static`
- `no-magic-values.md` — protocol values → enum/constant; tunables → config

Security posture:
- `owasp.md` — OWASP Top 10 (2021) coding rules tailored to this IdP (deny-by-default authz,
  crypto, injection/XXE, non-revealing errors, token/assertion integrity, SSRF)
- `zero-trust.md` — verify explicitly per request; no implicit trust zones; least privilege;
  assume breach (time-boxed privilege, revocation propagates); re-verify state on sensitive ops
- `step-up.md` — `@RequireStepUp` on destructive/privilege-escalating admin endpoints

Persistence & config:
- `flyway.md` — Flyway owns schema; `ddl-auto=validate`; migration + mapping change together
- `entity-design.md` — `AbstractEntity`/`AuditedEntity` bases; `@Embeddable` value objects
- `lazy-loading.md` — LAZY collections, `join fetch`, detached reads, OSIV-off traps, HHH000104
- `config-tunables.md` — externalize tunables; marker annotations read config, no baked defaults
- `boot4-autoconfig.md` — features need their `spring-boot-<feature>` module

Testing:
- `testing.md` — Testcontainers, MockMvc limits (OAuth2/SAML → `scripts/*.py`), TDD case matrix,
  structural-change checklist

Reviewer agents (purpose-routed, see `../.claude/agents/README.md`) complement these rules:
rules state the law, reviewers audit compliance. `security-reviewer` maps findings to OWASP
Top 10 and enforces `owasp.md` + `zero-trust.md`.
