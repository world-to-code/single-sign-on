-- SAML 2.0 service providers (relying parties) that trust this IdP.
CREATE TABLE saml_relying_party (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id              varchar(512) NOT NULL UNIQUE,   -- SP entityID
    acs_url                varchar(1024) NOT NULL,         -- Assertion Consumer Service URL
    name_id_format         varchar(256) NOT NULL DEFAULT 'urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress',
    want_assertions_signed boolean NOT NULL DEFAULT TRUE,
    created_at             timestamptz NOT NULL DEFAULT now()
);
