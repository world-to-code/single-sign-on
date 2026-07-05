-- Org-scope SAML relying parties: an RP is owned by the tenant whose admin registered it (org_id = that
-- org) or GLOBAL (org_id NULL — a platform-wide SP). The entityId stays GLOBALLY unique (an SP has one
-- SAML identity), so RLS — not a per-tier name split — provides the isolation: resolving an RP by entityId
-- during SSO runs in the request's bound tenant, so an org RP is only ever matched for its own tenant's
-- users (another tenant's RP is invisible → the SSO is refused), while a global RP resolves for everyone.
-- RLS mirrors V47 (global-default + org-override, tighter WITH CHECK).

ALTER TABLE saml_relying_party ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
CREATE INDEX idx_saml_relying_party_org ON saml_relying_party (org_id);

ALTER TABLE saml_relying_party ENABLE ROW LEVEL SECURITY;
ALTER TABLE saml_relying_party FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON saml_relying_party
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
