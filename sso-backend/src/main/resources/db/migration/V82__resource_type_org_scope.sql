-- Org-scope the resource-type vocabulary so a tenant admin can define its OWN types (e.g. its own BRANCH /
-- DEPARTMENT kinds) instead of the vocabulary being a platform-super-only global list. A type is GLOBAL
-- (org_id NULL — the shared BRANCH/DEPARTMENT/TEAM seeded vocabulary, visible to every tenant) or owned by
-- a tenant (org_id = that org). Its allowed-member rows carry the SAME org as their type. Mirrors the V43
-- role recipe (tier-aware partial uniqueness) and the V56 tightened WITH CHECK (a global row is writable
-- only from the platform / no-context state, so a tenant can never mint a global type). Existing rows
-- backfill to org NULL (the shared vocabulary is unchanged for everyone).

ALTER TABLE resource_type                ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
ALTER TABLE resource_type_allowed_member ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;

-- Replace the single global UNIQUE(name) with tier-aware partial uniqueness: one global type per name, and
-- one type per (org, name) within a tenant. Two tenants may each define a type named the same.
ALTER TABLE resource_type DROP CONSTRAINT resource_type_name_key;
CREATE UNIQUE INDEX uq_resource_type_name_global ON resource_type (name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_resource_type_org_name    ON resource_type (org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_resource_type_org               ON resource_type (org_id);
CREATE INDEX idx_resource_type_allowed_member_org ON resource_type_allowed_member (org_id);

ALTER TABLE resource_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_type FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON resource_type
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));

ALTER TABLE resource_type_allowed_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_type_allowed_member FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON resource_type_allowed_member
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));
