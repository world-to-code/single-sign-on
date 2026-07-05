-- Org-scope the `auth_policy` table (mirrors V43 role): a policy is either GLOBAL/default (org_id IS NULL —
-- e.g. the seeded "Default", resolved for every tenant) or a custom TENANT policy (org_id = owning org). RLS
-- isolates tenant policies while keeping global policies visible in every context — critical because auth
-- policies are resolved LIVE during the login flow (identify / each factor step), which runs before the
-- tenant context is bound; the org-scoped policies of the LOGIN org additionally resolve because the auth
-- flow now binds the login org around policy resolution (see AuthStateService), while the global (org_id
-- IS NULL) branch keeps the default policy resolvable even when no org is bound and lets the seeder create
-- the default policy at startup with no context.

ALTER TABLE auth_policy ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;

-- Tier-aware uniqueness: one global policy per name, one policy per (org, name) within a tenant.
ALTER TABLE auth_policy DROP CONSTRAINT auth_policy_name_key;
CREATE UNIQUE INDEX uq_auth_policy_name_global ON auth_policy (name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_auth_policy_org_name    ON auth_policy (org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_auth_policy_org ON auth_policy (org_id);

ALTER TABLE auth_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON auth_policy
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
