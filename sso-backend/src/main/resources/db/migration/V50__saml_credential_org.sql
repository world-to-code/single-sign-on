-- Per-tenant SAML signing credentials. The GLOBAL/platform IdP credential stays in the on-disk PKCS#12
-- keystore (unchanged); this table adds a per-organization signing credential (self-signed X.509 cert +
-- RSA private key, the private key encrypted at rest via SecretCipher), so a tenant's SAML assertions are
-- signed with its OWN key under its own host-derived entityID. A tenant without its own credential falls
-- back to the global keystore credential.
--
-- org_id is NOT NULL — only tenant credentials live here (the global one is the file keystore) — so RLS is
-- the strict membership form (no global-visible branch): a row is visible/writable only in its own org's
-- context or the platform context.

CREATE TABLE saml_credential (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    certificate text        NOT NULL,          -- Base64 X.509 (DER) self-signed certificate
    private_key text        NOT NULL,          -- Base64 PKCS#8 private key, encrypted at rest (SecretCipher)
    active      boolean     NOT NULL DEFAULT TRUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_saml_credential_active_org ON saml_credential (org_id, active);

ALTER TABLE saml_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE saml_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON saml_credential
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    );
