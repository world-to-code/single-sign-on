-- FIDO2/WebAuthn credentials (passkeys + roaming security keys) per user.
CREATE TABLE webauthn_credential (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    credential_id            text NOT NULL UNIQUE,   -- base64url
    attested_credential_data text NOT NULL,          -- base64 (AAGUID + credentialId + COSE public key)
    sign_count               bigint NOT NULL DEFAULT 0,
    transports               varchar(255),
    label                    varchar(120),
    created_at               timestamptz NOT NULL DEFAULT now()
);
