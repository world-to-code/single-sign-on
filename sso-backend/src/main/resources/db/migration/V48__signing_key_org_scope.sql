-- Org-scope the `signing_key` table so each tenant can hold its own OIDC signing key(s): a key is either
-- GLOBAL (org_id IS NULL — the platform key, and the fallback for tenants without their own) or owned by
-- one organization. The private key stays encrypted at rest via SecretCipher (unchanged). Existing rows
-- become global (org_id NULL), so token signing is unchanged until per-tenant keys are issued.
--
-- RLS mirrors V47 (global-default + org-override, tighter WITH CHECK): global keys are readable in every
-- context (the JWKS endpoint / startup key generation run with no org bound), a tenant's keys only in its
-- own context, and a GLOBAL key may be WRITTEN only from the platform or a no-context (seeder/startup)
-- window — never from a tenant-bound connection.

ALTER TABLE signing_key ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
CREATE INDEX idx_signing_key_active_org ON signing_key (org_id, active);

ALTER TABLE signing_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE signing_key FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON signing_key
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
