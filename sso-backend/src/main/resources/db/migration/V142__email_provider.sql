-- Sending email over an HTTP API, alongside SMTP.
--
-- SMTP is not always reachable: submission ports are commonly blocked outbound on corporate and hosted
-- networks, and a relay that cannot be connected to is not a configuration an administrator can fix from here.
-- The providers that matter all offer an HTTPS API on 443, which those networks do allow — so WHICH transport
-- a tenant uses becomes a setting, exactly as it already is for SMS.
--
-- The row keeps its table: it has always answered one question ("how does this tenant send mail"), and the
-- answer now has two shapes rather than one.
ALTER TABLE smtp_settings
    ADD COLUMN provider          text NOT NULL DEFAULT 'SMTP',
    ADD COLUMN api_key_encrypted text;                          -- SecretCipher ciphertext, never plaintext

-- host/port describe an SMTP relay and mean nothing to an HTTP provider, so they stop being universally
-- required. What replaces the NOT NULL is a per-provider rule: each shape must be complete for its own kind,
-- because a half-configured row is a tenant whose mail silently stops.
ALTER TABLE smtp_settings ALTER COLUMN host DROP NOT NULL;
ALTER TABLE smtp_settings ALTER COLUMN port DROP NOT NULL;

ALTER TABLE smtp_settings
    ADD CONSTRAINT smtp_settings_relay_complete
        CHECK (provider <> 'SMTP' OR (host IS NOT NULL AND port IS NOT NULL)),
    ADD CONSTRAINT smtp_settings_api_key_present
        CHECK (provider = 'SMTP' OR api_key_encrypted IS NOT NULL);
