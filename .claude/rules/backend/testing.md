---
paths:
  - "sso-backend/src/test/**"
  - "scripts/**"
---

# Backend testing

- `./gradlew test` runs against **Testcontainers** (Docker required). Context startup runs
  Hibernate `validate`, so entity↔schema drift fails every test run — that's intended
  ([flyway](flyway.md)).
- **MockMvc misparses `/oauth2/authorize` and SAML query strings** — do NOT write MockMvc tests
  for those endpoints; verify them live with `scripts/oidc_authcode_flow.py`,
  `scripts/saml_sso_flow.py`, `scripts/admin_api_flow.py` against `bootRun`.
- **TDD:** write the full case matrix BEFORE implementing (happy, each error path, boundaries,
  principal matrix for authz endpoints), then code to green.
- Adapter/read-model projection tests must run OUTSIDE a transaction — see
  [lazy-loading](lazy-loading.md).
- **After structural changes**, the definition of done is: `./gradlew compileJava` +
  `ModularityTests` + full `./gradlew test` green, plus `rg` sweeps for zero inline FQNs
  ([imports](imports.md)) and zero cross-module entity imports
  ([entity-hiding](entity-hiding.md)).

Related reviewer: `.claude/agents/test-quality-reviewer.md`.
