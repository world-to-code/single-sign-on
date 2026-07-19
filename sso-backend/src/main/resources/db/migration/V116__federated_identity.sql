-- The durable link between an upstream identity (the issuer that minted it plus the stable `sub` it asserts)
-- and the local account it belongs to. Federated login previously matched on EMAIL alone, which is the wrong
-- key twice over: an upstream address may change (the account is then re-provisioned as a duplicate, orphaning
-- the original's groups and roles) and an address may be reassigned to a different person (who would inherit
-- the previous holder's account). `subject` is the identifier OIDC actually guarantees to be stable.
CREATE TABLE federated_identity (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NOT NULL, unlike identity_provider: a link always belongs to a tenant. Federated sign-in resolves an
    -- org before it starts (auth.org.selectFirst) and is org-strict, so there is no org-less link to record.
    org_id         uuid         NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    -- The UPSTREAM's identity, not the tenant's label for it. identity_provider.alias is mutable and may be
    -- repointed at a different IdP; keying on the alias would silently carry every link over to the new
    -- upstream, where a colliding `sub` would inherit the account. The alias is kept for display only.
    issuer         text         NOT NULL,
    subject        varchar(255) NOT NULL,   -- the id_token `sub`; bounded so it always fits a btree index entry
    provider_alias varchar(64)  NOT NULL,   -- display/diagnostics; NOT part of the identity key
    user_id        uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at     timestamptz  NOT NULL DEFAULT now()
);

-- One upstream identity maps to ONE local account per tenant. The org leads the key on purpose: an
-- organization owns its own app_user rows (V68), so the SAME upstream subject legitimately maps to different
-- accounts in different tenants — a person may hold an account in more than one organization.
CREATE UNIQUE INDEX uq_federated_identity ON federated_identity (org_id, issuer, subject);
-- Supports "does this account already have an identity at this issuer?" (the guard that stops a second
-- subject from claiming an account by email) and listing/unlinking a user's federated identities.
CREATE INDEX ix_federated_identity_user ON federated_identity (org_id, issuer, user_id);

ALTER TABLE federated_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE federated_identity FORCE ROW LEVEL SECURITY;
-- STRICT per-tier, mirroring identity_provider (V114): a link is never inherited across tiers, so a tenant
-- must not even READ another tier's rows. The service reads are explicitly org-scoped too; this is the
-- storage-layer backstop, so a future unscoped read cannot resolve a login to another tenant's account.
CREATE POLICY org_isolation ON federated_identity
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    );
