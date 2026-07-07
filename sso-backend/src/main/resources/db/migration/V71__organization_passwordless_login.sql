-- Per-organization admin toggle: allow passwordless passkey (WebAuthn/FIDO2) sign-in as the first factor.
-- Defaults to false so passwordless login is opt-in per tenant; enforced server-side at login completion.
ALTER TABLE organization ADD COLUMN passwordless_login_enabled boolean NOT NULL DEFAULT false;
