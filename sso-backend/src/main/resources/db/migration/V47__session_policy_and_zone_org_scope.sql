-- Org-scope `session_policy` and `network_zone` (mirrors V43/V46): each row is either GLOBAL/default
-- (org_id IS NULL — the seeded Default session policy, platform-wide zones/policies) or owned by one
-- tenant (org_id = owning org). Unlike auth policies these are resolved POST-auth from an in-memory cache
-- (loaded as platform so it holds every tenant's rows) and the effective policy is then filtered to the
-- request's bound org + globals in code.
--
-- RLS shape:
--  * USING (read) keeps global rows visible in EVERY context (platform, any tenant, and the no-context
--    windows: startup seeding and the platform cache load), plus each tenant sees only its own rows.
--  * WITH CHECK (write) is TIGHTER than the read side: a GLOBAL (org_id IS NULL) row may be written only
--    from the platform context OR a no-context window (the seeder) — NOT from a tenant-bound connection.
--    So RLS itself refuses a tenant creating a global policy/zone, rather than relying solely on the
--    application stamping org_id. (V43/V46 use the looser `OR org_id IS NULL`; they are brought in line
--    when the dedicated non-superuser runtime role lands.)

-- ---- session_policy -------------------------------------------------------------------------------
ALTER TABLE session_policy ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;

ALTER TABLE session_policy DROP CONSTRAINT uq_session_policy_name;
CREATE UNIQUE INDEX uq_session_policy_name_global ON session_policy (name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_session_policy_org_name    ON session_policy (org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_session_policy_org ON session_policy (org_id);

ALTER TABLE session_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE session_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON session_policy
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

-- ---- network_zone ---------------------------------------------------------------------------------
ALTER TABLE network_zone ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;

ALTER TABLE network_zone DROP CONSTRAINT network_zone_name_key;
CREATE UNIQUE INDEX uq_network_zone_name_global ON network_zone (name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_network_zone_org_name    ON network_zone (org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_network_zone_org ON network_zone (org_id);

ALTER TABLE network_zone ENABLE ROW LEVEL SECURITY;
ALTER TABLE network_zone FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON network_zone
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
