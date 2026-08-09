-- Where the audit trail is shipped: one collector for the whole deployment.
--
-- Unlike smtp_settings and sms_settings this is deliberately NOT per-tenant, and the difference is the point.
-- A tenant configuring its own collector would be choosing where its own security history goes, which is the
-- one thing a tenant must not be able to redirect; and the exporter reads ACROSS tenants, so per-tenant rows
-- would have no coherent meaning for it anyway. Hence a single row, enforced (id = 1) rather than assumed —
-- the same shape audit_chain_head uses, and for the same reason: "there is exactly one" is a fact the schema
-- can hold, not a convention every writer has to remember.
--
-- No RLS. There is nothing to isolate: one platform-owned row, reached only by a PLATFORM-only permission
-- (audit:export). Adding a policy here would suggest a tenant dimension that does not exist.

CREATE TABLE audit_export_settings (
    id                   SMALLINT    PRIMARY KEY CHECK (id = 1),   -- exactly one, enforced
    -- The collector endpoint. Validated https-only and against the SSRF host rules BOTH when written and again
    -- at USE: configuration outlives the check that admitted it, and this payload is the complete security
    -- history of every tenant with the collector credential riding along.
    endpoint_url         text        NOT NULL CHECK (length(endpoint_url) BETWEEN 1 AND 2048),
    -- SecretCipher ciphertext, never plaintext — the bearer the collector authenticates us by.
    credential_encrypted text        NOT NULL,
    enabled              boolean     NOT NULL DEFAULT false,       -- configured is not the same as switched on
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           uuid REFERENCES app_user (id) ON DELETE SET NULL
);

-- The FK's own index (a single-row table makes this near-free; stated rather than left to chance).
CREATE INDEX idx_audit_export_settings_updated_by ON audit_export_settings (updated_by);
