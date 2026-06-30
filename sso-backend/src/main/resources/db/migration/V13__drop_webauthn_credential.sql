-- Consolidated on Spring Security's single WebAuthn store (user_credentials/user_entities);
-- the separate webauthn4j second-factor table is no longer used.
DROP TABLE IF EXISTS webauthn_credential;
