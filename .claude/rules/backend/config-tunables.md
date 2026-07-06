---
paths:
  - "sso-backend/src/main/java/**/*.java"
  - "sso-backend/src/main/resources/application*.yml"
---

# Externalize tunables — no hardcoded numbers

Every tunable (timeout, window, limit, size, retry count, TTL) lives in `application.yml` and is
read via `@Value` (or a `@ConfigurationProperties` record). Never a literal in code.

- **Annotation defaults are code too.** Do not bake a number into an annotation attribute
  default — make the annotation a **marker** and have its interceptor/aspect read the value from
  config or policy (e.g. `@RequireStepUp` is a bare marker; `StepUpInterceptor` reads the
  freshness windows from the session policy).
- **Protocol constants are NOT tunables** — spec-fixed values (token type names, claim names,
  algorithm identifiers) stay named constants ([no-magic-values](no-magic-values.md)).
- New config keys go under the existing `sso.*` namespace with a sensible default in
  `application.yml`, so behavior is visible and overridable per environment.

```java
// DO: value from application.yml (real example: sso.lockout.*)
@Value("${sso.lockout.max-attempts}")
private int maxAttempts;

// DO: marker annotation — the interceptor resolves the tunable (session policy / config)
@RequireStepUp
@DeleteMapping("/{id}")

// DON'T
@RequireStepUp(maxAgeSeconds = 300)     // tunable frozen into an annotation default
if (failures >= 5) { lock(user); }      // literal in code — belongs in sso.lockout.max-attempts
```

Related: [no-magic-values](no-magic-values.md), [step-up](step-up.md).
