-- Org-scope the resource DAG (Map A: organization = the customer-company tenant; the resource tree models
-- its branches/departments). A resource is owned by the tenant whose admin created it (org_id) or GLOBAL
-- (org_id NULL — the platform-wide resources that predate this migration). The edges, leaf members and
-- delegation grants carry the SAME org as their owning resource, so the recursive subtree walks
-- (ResourceRepository CTEs over resource_edge/resource_role) are RLS-confined to the caller's tenant + global.
-- resource_type / resource_type_allowed_member stay GLOBAL (a shared type vocabulary, not tenant data).
-- Existing rows backfill to org NULL (stay platform-managed until migrated). Mirrors the V47/V52/V53 recipe
-- (global-default + org-override, tightened WITH CHECK: a global write only from the platform/no-context state).

ALTER TABLE resource        ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
ALTER TABLE resource_edge   ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
ALTER TABLE resource_member ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
ALTER TABLE resource_role   ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;

CREATE INDEX idx_resource_org        ON resource (org_id);
CREATE INDEX idx_resource_edge_org   ON resource_edge (org_id);
CREATE INDEX idx_resource_member_org ON resource_member (org_id);
CREATE INDEX idx_resource_role_org   ON resource_role (org_id);

ALTER TABLE resource ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON resource
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));

ALTER TABLE resource_edge ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_edge FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON resource_edge
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));

ALTER TABLE resource_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_member FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON resource_member
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));

ALTER TABLE resource_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_role FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON resource_role
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));
