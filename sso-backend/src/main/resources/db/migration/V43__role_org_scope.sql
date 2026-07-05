-- Org-scope the `role` table: a role is either GLOBAL/system (org_id IS NULL — visible to every tenant,
-- e.g. ROLE_ADMIN/ROLE_USER) or a custom TENANT role (org_id = the owning organization). RLS isolates
-- tenant roles while keeping global roles visible in every context — critical because login authority
-- resolution reads `role` in the UNSET context (before the org context is bound), and the seeder creates
-- the system roles at startup with no context; both must still see/write the global (org_id IS NULL) rows.

ALTER TABLE role ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;

-- Replace the global UNIQUE(name) with tier-aware partial uniqueness: one global role per name, and one
-- role per (org, name) within a tenant. Two tenants may each have a role named the same.
ALTER TABLE role DROP CONSTRAINT role_name_key;
CREATE UNIQUE INDEX uq_role_name_global ON role (name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_role_org_name    ON role (org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_role_org ON role (org_id);

-- Row-Level Security (mirrors V42): the app connects as the table owner, so FORCE is required. A NULL
-- org_id (global/system role) is always visible AND writable — so the seeder (no context) can create
-- system roles and login authority resolution (unset context) still sees them; a tenant's own roles are
-- visible/writable only in that org's context; platform context sees all. current_setting(...,true) is
-- NULL (→ '') when unset, matching no tenant row (fail-closed) but still permitting the NULL branch.
ALTER TABLE role ENABLE ROW LEVEL SECURITY;
ALTER TABLE role FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON role
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id IS NULL
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id IS NULL
        OR org_id::text = current_setting('app.current_org', true)
    );
