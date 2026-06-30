-- Bearer tokens used by external IdPs/HR systems to authenticate to the SCIM 2.0 server.
-- Only the SHA-256 hash of each token is stored; the plaintext is shown once at issuance.
CREATE TABLE scim_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    description varchar(200),
    token_hash  varchar(128) NOT NULL UNIQUE,
    enabled     boolean NOT NULL DEFAULT TRUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz
);
