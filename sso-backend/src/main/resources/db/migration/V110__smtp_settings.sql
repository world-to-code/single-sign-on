-- Per-tenant SMTP: a customer company sends its onboarding/notification email from ITS OWN mail server. A row
-- here is the acting tier's override; a tenant with no row inherits the platform default (spring.mail.* in
-- application.yml, i.e. the framework JavaMailSender bean). A NULL org_id is an optional platform-wide override
-- of that default, editable only by a platform super-admin. The password is stored SecretCipher-encrypted
-- (AES-256-GCM, "encg:" prefix) — the plaintext never touches the DB, a log, an audit row, or a view.
CREATE TABLE smtp_settings (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             uuid REFERENCES organization (id) ON DELETE CASCADE,  -- NULL = platform-wide override
    host               text        NOT NULL,
    port               int         NOT NULL,
    username           text,                                                 -- NULL = unauthenticated relay
    password_encrypted text,                                                 -- SecretCipher ciphertext, never plaintext
    from_address       text,                                                 -- From header; NULL = leave unset
    starttls           boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- One row per tenant, and at most one platform-wide row (a plain UNIQUE(org_id) can't enforce the single-global
-- case because NULLs compare distinct — the V85/V53 tier-aware partial-index recipe).
CREATE UNIQUE INDEX uq_smtp_settings_global ON smtp_settings ((true)) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_smtp_settings_org    ON smtp_settings (org_id) WHERE org_id IS NOT NULL;

ALTER TABLE smtp_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE smtp_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON smtp_settings
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
