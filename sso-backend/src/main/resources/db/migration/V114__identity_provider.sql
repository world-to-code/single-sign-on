-- Inbound federation: an upstream OIDC provider (Google, Okta, Azure AD, any OIDC IdP) a tenant's users sign
-- in through. A row is the acting tier's provider; a NULL org_id is a platform-tier provider (the super-admin's
-- own login), editable only by a platform super-admin. The client secret is stored SecretCipher-encrypted
-- (AES-256-GCM, "encg:" prefix) — the plaintext never touches the DB, a log, an audit row, or a view.
CREATE TABLE identity_provider (
    id                      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  uuid REFERENCES organization (id) ON DELETE CASCADE,  -- NULL = platform-tier provider
    alias                   varchar(64) NOT NULL,                                 -- URL-safe handle, unique per tier
    display_name            text        NOT NULL,
    issuer_uri              text        NOT NULL,                                 -- OIDC issuer (discovery root)
    client_id               text        NOT NULL,
    client_secret_encrypted text,                                                 -- SecretCipher ciphertext
    scopes                  text        NOT NULL,                                 -- space-separated; includes openid
    allow_jit_provisioning  boolean     NOT NULL DEFAULT false,
    enabled                 boolean     NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now()
);

-- The alias is unique within a tier. A plain UNIQUE(org_id, alias) can't pin the global tier because NULLs
-- compare distinct, so the platform tier gets its own partial index (the V110/V85 tier-aware recipe).
CREATE UNIQUE INDEX uq_identity_provider_org ON identity_provider (org_id, alias) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX uq_identity_provider_global ON identity_provider (alias) WHERE org_id IS NULL;

ALTER TABLE identity_provider ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_provider FORCE ROW LEVEL SECURITY;
-- STRICT per-tier (unlike smtp_settings, whose USING keeps the global row readable for send-time inheritance):
-- federation providers are NOT inherited, so a tenant must not even READ the platform tier's global rows. The
-- platform tier still reads them via the app.platform branch; the service reads are also explicitly org-scoped,
-- so this is the RLS backstop for that invariant (a future unscoped read cannot leak a global provider cross-tier).
CREATE POLICY org_isolation ON identity_provider
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );
