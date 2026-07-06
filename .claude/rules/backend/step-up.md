---
paths:
  - "sso-backend/src/main/java/**/internal/api/**/*.java"
---

# Step-up authentication for sensitive admin actions

Destructive or privilege-escalating endpoints are marked `@RequireStepUp`. The set (keep it
complete when adding endpoints):

- **all `*:delete` operations**,
- policy create/update,
- role/permission grants,
- group role & manager delegation,
- key/secret rotation.

`StepUpInterceptor` (in `session`) enforces the **session policy** (DB-backed, admin-configurable —
not an application.yml key):

- ordinary mutations (POST/PUT/DELETE/PATCH) are gated by the policy's idle-based
  `reauthIntervalMinutes` — activity keeps the clock fresh, reads are never gated;
- `@RequireStepUp` actions require a DELIBERATE re-auth via `/api/auth/reauth` within the
  policy's `sensitiveReauthWindowMinutes`, satisfied with the policy's `stepUpFactors` (a plain
  login never counts).

On stale elevation the response is a `401` with the `X-Step-Up-Required` header listing the
allowed factors; the SPA prompts and retries.

When adding an admin endpoint, decide explicitly: does it destroy data or expand someone's
authority? If yes → `@RequireStepUp` **in addition to** its `@PreAuthorize` permission check
(step-up is a freshness gate, not an authorization gate — you always need both).

Related: [config-tunables](config-tunables.md) (`@RequireStepUp` is a marker — the windows live
in the session policy, never in annotation attributes); reviewer:
`.claude/agents/security-reviewer.md`.
