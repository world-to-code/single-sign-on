-- Org-scope SCIM bearer tokens: a token is owned by the tenant whose admin issued it (org_id = that org),
-- or GLOBAL (org_id NULL — a platform-wide token, e.g. the dev seed / a super-admin's cross-tenant token).
-- Authenticating a SCIM request with a token binds that token's org for the whole request, so SCIM
-- provisioning runs — and is RLS-confined — within the token's tenant. Only the SHA-256 hash is stored
-- (unchanged). RLS mirrors V47 (global-default + org-override, tighter WITH CHECK): a global token is
-- writable only from the platform or a no-context (seeder) window, never from a tenant-bound connection.

ALTER TABLE scim_token ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
CREATE INDEX idx_scim_token_org ON scim_token (org_id);

ALTER TABLE scim_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE scim_token FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON scim_token
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
