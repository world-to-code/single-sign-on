-- Per-relying-party SAML security settings + the SP's certificates, for production-grade
-- IdP behavior (signing, encryption incl. legacy algorithms, SP-signed AuthnRequest verification,
-- and IdP-initiated SSO).
ALTER TABLE saml_relying_party ADD COLUMN sign_assertion           boolean NOT NULL DEFAULT TRUE;
ALTER TABLE saml_relying_party ADD COLUMN sign_response            boolean NOT NULL DEFAULT FALSE;
ALTER TABLE saml_relying_party ADD COLUMN encrypt_assertion        boolean NOT NULL DEFAULT FALSE;
ALTER TABLE saml_relying_party ADD COLUMN signature_algorithm      varchar(64)  NOT NULL DEFAULT 'RSA_SHA256';
ALTER TABLE saml_relying_party ADD COLUMN data_encryption_algorithm varchar(32) NOT NULL DEFAULT 'AES256_GCM';
ALTER TABLE saml_relying_party ADD COLUMN key_transport_algorithm  varchar(32)  NOT NULL DEFAULT 'RSA_OAEP';
ALTER TABLE saml_relying_party ADD COLUMN want_authn_requests_signed boolean NOT NULL DEFAULT FALSE;
ALTER TABLE saml_relying_party ADD COLUMN allow_idp_initiated      boolean NOT NULL DEFAULT TRUE;
ALTER TABLE saml_relying_party ADD COLUMN signing_certificate      text;  -- SP cert (PEM) to verify SP-signed AuthnRequests
ALTER TABLE saml_relying_party ADD COLUMN encryption_certificate   text;  -- SP cert (PEM) to encrypt assertions to the SP

-- migrate the legacy column: old want_assertions_signed -> new sign_assertion (keep both briefly)
UPDATE saml_relying_party SET sign_assertion = want_assertions_signed;
ALTER TABLE saml_relying_party DROP COLUMN want_assertions_signed;
