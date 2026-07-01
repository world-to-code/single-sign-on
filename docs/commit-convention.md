# Commit Message Convention

This repository follows **Conventional Commits**. Every commit message must match:

```
<type>(<scope>): <subject>

<body>

<footer>
```

## Subject line (required)

- `<type>` — one of:
  - `feat` — a new user-facing feature
  - `fix` — a bug fix
  - `refactor` — a code change that neither fixes a bug nor adds a feature
  - `perf` — a performance improvement
  - `docs` — documentation only
  - `test` — adding or fixing tests only
  - `build` — build system, dependencies, Gradle/Docker
  - `chore` — housekeeping that doesn't touch src/main or tests
  - `style` — formatting / whitespace / import ordering only
- `<scope>` — optional, the affected module or area, e.g. `admin`, `auth`,
  `authpolicy`, `user`, `portal`, `saml`, `scim`, `session`, `mfa`, `crypto`,
  `security`, `config`, `webauthn`, `oidc`, `audit`, `ratelimit`, `bootstrap`,
  `web`, `shared`, `frontend`, `build`. Omit when a change is truly cross-cutting.
- `<subject>` — imperative mood, lowercase first letter, **no trailing period**,
  ≤ 72 characters. "add", not "added"/"adds".

## Body (optional)

- Blank line after the subject, then explain **what** and **why** (not how).
- Wrap at ~72 columns. Use `-` bullets for multiple points.

## Footer (optional)

- Breaking changes: a paragraph starting with `BREAKING CHANGE: ...`.
- Issue refs: `Refs #123` / `Closes #123`.
- **Never** add `Co-Authored-By` (or any co-author) trailers.

## Examples

```
refactor(admin): back registered-client admin with JPA instead of raw JDBC
fix(auth): reject a replayed TOTP time-step at challenge time
feat(portal): add per-app step-up with a freshness window
docs: add commit message convention
```
