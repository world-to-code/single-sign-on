-- The admin console now runs on a SESSION POLICY, selected per tenant, instead of a parallel settings axis.
--
-- 1. The two knobs that were genuinely admin-console-specific move ONTO the session policy, joining the
--    step-up windows the console already read from it (V75). A policy therefore describes a complete
--    session posture — general AND admin-console — in one place.
-- 2. admin_portal_settings keeps a single column: WHICH session policy governs the console for this tenant
--    (NULL = the policy resolved for the acting admin, the pre-existing behaviour).

-- session_policy is FORCE ROW LEVEL SECURITY: without this, a migration owner that is not a superuser (the
-- hardened prod setup) sees only GLOBAL rows, so the backfill below would silently skip every tenant policy
-- and reset its elevation TTL to the column default and its admin allowlist to NULL (= any network). The
-- org_isolation policy honours this GUC explicitly (V47).
SET LOCAL app.platform = 'on';

ALTER TABLE session_policy
    ADD COLUMN elevation_token_ttl_minutes int NOT NULL DEFAULT 5,
    ADD COLUMN admin_allowed_cidrs text;

-- Carry each tier's current console settings onto that tier's policies, so behaviour is unchanged on upgrade.
UPDATE session_policy p
SET elevation_token_ttl_minutes = s.elevation_token_ttl_minutes,
    admin_allowed_cidrs         = s.admin_allowed_cidrs
FROM admin_portal_settings s
WHERE s.org_id IS NOT DISTINCT FROM p.org_id;

-- A tenant with no settings row of its own inherited the global default; carry that too.
UPDATE session_policy p
SET elevation_token_ttl_minutes = s.elevation_token_ttl_minutes,
    admin_allowed_cidrs         = s.admin_allowed_cidrs
FROM admin_portal_settings s
WHERE s.org_id IS NULL
  AND p.org_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM admin_portal_settings o WHERE o.org_id = p.org_id);

ALTER TABLE admin_portal_settings
    DROP COLUMN elevation_token_ttl_minutes,
    DROP COLUMN admin_allowed_cidrs,
    -- ON DELETE RESTRICT: a policy governing a console must be de-selected before it can be deleted. The
    -- alternative (SET NULL) silently reverts the console to the acting admin's own policy, DROPPING that
    -- tenant's admin IP allowlist and lengthening its elevation TTL — a fail-open network control.
    ADD COLUMN session_policy_id uuid REFERENCES session_policy (id) ON DELETE RESTRICT;

CREATE INDEX idx_admin_portal_settings_policy ON admin_portal_settings (session_policy_id);

-- Pin each tier's console to the tier's OWN Default policy, which now carries the values backfilled above.
-- Without this every row stays NULL and the console resolves the ACTING ADMIN's policy — which, for an org
-- whose admins resolve to the GLOBAL Default, silently drops that tenant's console TTL/allowlist.
UPDATE admin_portal_settings s
SET session_policy_id = p.id
FROM session_policy p
WHERE p.org_id IS NOT DISTINCT FROM s.org_id
  AND p.name = 'Default';
