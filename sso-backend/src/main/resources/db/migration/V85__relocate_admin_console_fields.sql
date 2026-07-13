-- Relocate the two ADMIN-CONSOLE-ONLY knobs off session_policy into a per-tenant admin-console overlay.
--
-- elevation_token_ttl_minutes (elevation proof lifetime) and admin_allowed_cidrs (console entry IP allowlist)
-- were carried on EVERY session policy (V77), though they mean something only for the admin console. They now
-- live in admin_console_config, edited once in the admin console's settings, per tenant (own row) with a GLOBAL
-- default (org_id NULL) that tenants inherit. The rest of a session policy (timeouts, reauth, step-up window)
-- stays put — those apply to any app. The console still reads its step-up freshness from the session policy.

CREATE TABLE admin_console_config (
    id                          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                      uuid REFERENCES organization (id) ON DELETE CASCADE,  -- NULL = GLOBAL default
    elevation_token_ttl_minutes int         NOT NULL,
    admin_allowed_cidrs         text,                                                 -- NULL/blank = any network
    created_at                  timestamptz NOT NULL DEFAULT now()
);

-- One row per tenant, and exactly one GLOBAL row (a plain UNIQUE(org_id) can't enforce the single-global case
-- because NULLs compare distinct — mirrors the V53/V83 tier-aware partial-index recipe, minus the subject axis).
CREATE UNIQUE INDEX uq_admin_console_config_global ON admin_console_config ((true)) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_admin_console_config_org    ON admin_console_config (org_id) WHERE org_id IS NOT NULL;

ALTER TABLE admin_console_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_console_config FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON admin_console_config
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id IS NULL
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );

-- FORCE RLS: a non-superuser migration owner (hardened prod) would otherwise see only GLOBAL rows and skip
-- every tenant policy in the backfill, silently resetting each tenant's console TTL to a default and its
-- allowlist to NULL (= any network) — a fail-open network control. The org_isolation policy honours this GUC.
SET LOCAL app.platform = 'on';

-- Backfill FAITHFULLY from the value each console reads TODAY. The console resolves its governing policy as:
-- its PORTAL/admin all-subjects binding IF one exists, ELSE the tier's own Default policy (the fallback in
-- AdminConsolePolicy.resolveFor). Both branches carried the elevation TTL + allowlist on that session policy,
-- so copy from whichever actually governs — missing the Default-fallback branch would silently WIDEN a
-- restricted allowlist (or lengthen a shortened TTL) to the global default, a fail-open network control.

-- (1) Tiers WITH an explicit PORTAL/admin binding: copy the BOUND policy's values.
INSERT INTO admin_console_config (org_id, elevation_token_ttl_minutes, admin_allowed_cidrs)
SELECT b.org_id, sp.elevation_token_ttl_minutes, sp.admin_allowed_cidrs
FROM policy_binding b
JOIN session_policy sp ON sp.id = b.session_policy_id
WHERE b.app_type = 'PORTAL' AND b.app_id = 'admin' AND b.subject_type IS NULL
  AND b.session_policy_id IS NOT NULL;

-- (2) Tiers WITHOUT a binding: copy that tier's own Default policy (what the console fell back to). Excludes
-- tiers already inserted by (1); the tier-aware unique indexes make any residual overlap fail loudly, not
-- silently double-insert.
INSERT INTO admin_console_config (org_id, elevation_token_ttl_minutes, admin_allowed_cidrs)
SELECT sp.org_id, sp.elevation_token_ttl_minutes, sp.admin_allowed_cidrs
FROM session_policy sp
WHERE sp.name = 'Default'
  AND NOT EXISTS (
      SELECT 1 FROM policy_binding b
      WHERE b.app_type = 'PORTAL' AND b.app_id = 'admin' AND b.subject_type IS NULL
        AND b.session_policy_id IS NOT NULL
        AND b.org_id IS NOT DISTINCT FROM sp.org_id);

-- (3) Safety net: guarantee a GLOBAL default row even on a fresh install where no Default policy exists yet
-- (it is seeded at startup, after migrations). 5 minutes / NULL allowlist = the historical column defaults.
INSERT INTO admin_console_config (org_id, elevation_token_ttl_minutes, admin_allowed_cidrs)
SELECT NULL, 5, NULL
WHERE NOT EXISTS (SELECT 1 FROM admin_console_config WHERE org_id IS NULL);

-- The two knobs are gone from the session policy: a policy no longer describes admin-console posture.
ALTER TABLE session_policy
    DROP COLUMN elevation_token_ttl_minutes,
    DROP COLUMN admin_allowed_cidrs;
