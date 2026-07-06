---
paths:
  - "sso-backend/build.gradle"
  - "sso-backend/src/main/resources/application*.yml"
---

# Spring Boot 4 modular auto-configuration

Boot 4 splits auto-configuration per feature: a feature only auto-configures when its dedicated
`spring-boot-<feature>` module is on the classpath (e.g. Flyway needs `spring-boot-flyway`, not
just the `flyway-core` dependency).

Symptom of a missing module: the dependency is present, properties are set, but the feature
silently does nothing at startup. Before debugging config values, check the classpath has the
matching `spring-boot-<feature>` module.

Versions are pinned in `build.gradle` — keep new dependencies pinned the same way.
