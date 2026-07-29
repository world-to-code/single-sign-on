-- Per-tenant SMS gateway: a customer company sends its own one-time codes through its own provider account.
-- The mirror of V110 smtp_settings, and deliberately the same shape — a row is the acting tier's override, a
-- NULL org_id is an optional platform-wide override editable only by a platform super-admin, and a tenant with
-- no row falls back to that (and, failing both, to the dev logging sender). The API secret is stored
-- SecretCipher-encrypted (AES-256-GCM, "encg:" prefix): the plaintext never touches the DB, a log, an audit
-- row, or a view.
--
-- provider names WHICH gateway the credentials belong to. It is a code-bound enum rather than free text
-- because each value implies an endpoint, an authentication scheme and a payload shape that only the matching
-- client knows how to build; a value with no client would be a row that silently sends nothing.
--
-- sender_number is the number messages come FROM. In Korea it is not cosmetic: carriers require the sending
-- number to be pre-registered (발신번호 사전등록) against the account, so a mismatched value is rejected by the
-- provider rather than delivered from a different number.
CREATE TABLE sms_settings (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid REFERENCES organization (id) ON DELETE CASCADE,  -- NULL = platform-wide override
    provider             text        NOT NULL,
    api_key              text        NOT NULL,                                 -- Solapi API key / Twilio Account SID
    api_secret_encrypted text        NOT NULL,                                 -- SecretCipher ciphertext, never plaintext
    sender_number        text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);

-- One row per tenant, and at most one platform-wide row (a plain UNIQUE(org_id) cannot express the single-global
-- case because NULLs compare distinct — the V110/V85/V53 tier-aware partial-index recipe).
CREATE UNIQUE INDEX uq_sms_settings_global ON sms_settings ((true)) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_sms_settings_org    ON sms_settings (org_id) WHERE org_id IS NOT NULL;

ALTER TABLE sms_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE sms_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON sms_settings
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
