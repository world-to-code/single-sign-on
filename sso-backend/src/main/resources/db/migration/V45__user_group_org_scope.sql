-- Org-scope the `user_group` table (mirrors V43 role): a group is either GLOBAL/system (org_id IS NULL —
-- visible everywhere, e.g. the "All Users" group) or a custom TENANT group (org_id = the owning org). RLS
-- isolates tenant groups while keeping global groups visible in every context — critical because the
-- group-delegated-role resolution reads `user_group` during login (the completion service binds the login
-- org so a member's org groups + global groups both resolve), and AllUsersGroupSeeder seeds "All Users" at
-- startup with no context; both must still see/write the global (org_id IS NULL) rows.

ALTER TABLE user_group ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;

-- Tier-aware uniqueness: one global group per name, one group per (org, name) within a tenant.
ALTER TABLE user_group DROP CONSTRAINT user_group_name_key;
CREATE UNIQUE INDEX uq_user_group_name_global ON user_group (name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_user_group_org_name    ON user_group (org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_user_group_org ON user_group (org_id);

ALTER TABLE user_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_group FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON user_group
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
