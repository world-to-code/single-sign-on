-- Inbound federation gains a SECOND protocol: an upstream SAML IdP beside the existing OIDC one. One table (not
-- a sibling) because `alias` is the login route's path segment (/api/auth/federation/{alias}/start) and must
-- stay unique across protocols within a tier — two tables would allow a collision the route could not resolve.
alter table identity_provider add column protocol varchar(16) not null default 'OIDC';
alter table identity_provider add constraint identity_provider_protocol_known
    check (protocol in ('OIDC', 'SAML'));

-- The OIDC columns become nullable so a SAML row can exist; the protocol-conditional CHECK below re-imposes
-- them for OIDC rows, so widening cannot weaken the existing invariant.
alter table identity_provider alter column issuer_uri drop not null;
alter table identity_provider alter column client_id drop not null;
alter table identity_provider alter column client_secret_encrypted drop not null;
alter table identity_provider alter column scopes drop not null;

-- SAML config. idp_entity_id is the upstream's EntityID; the link namespace derives from it but is QUALIFIED
-- (see IdentityProviderServiceImpl.upstreamIssuerOf) so a SAML EntityID can never collide with an OIDC issuer.
-- signing_certificate is the PEM an assertion MUST verify against — a pinned key, not a metadata-resolved
-- trust store. name_id_format is required: an unset format lets the upstream choose, including a transient
-- pseudonym that resolves to no link and JIT-provisions a duplicate account on every sign-in.
alter table identity_provider add column idp_entity_id     varchar(1024);
alter table identity_provider add column sso_url           text;
alter table identity_provider add column signing_certificate text;
alter table identity_provider add column name_id_format    varchar(128);

-- Exactly one protocol's config is present, and it is complete. Expressed here rather than in the service so a
-- half-configured provider cannot exist at all (a SAML row with no certificate would be an unverified login).
alter table identity_provider add constraint identity_provider_protocol_config check (
    (protocol = 'OIDC'
        and issuer_uri is not null and client_id is not null
        and client_secret_encrypted is not null and scopes is not null
        and idp_entity_id is null and sso_url is null
        and signing_certificate is null and name_id_format is null)
 or (protocol = 'SAML'
        and idp_entity_id is not null and sso_url is not null and signing_certificate is not null
        and name_id_format is not null
        and issuer_uri is null and client_id is null
        and client_secret_encrypted is null and scopes is null));

-- One provider per upstream EntityID per tier: federated_identity is keyed (org_id, issuer, subject), so two
-- aliases pointing at the same upstream would share links and each could resolve the other's identities.
-- Tier-aware pair, because NULL org_id compares distinct in a plain UNIQUE.
create unique index uq_identity_provider_saml_entity_org on identity_provider (org_id, idp_entity_id)
    where protocol = 'SAML' and org_id is not null;
create unique index uq_identity_provider_saml_entity_global on identity_provider (idp_entity_id)
    where protocol = 'SAML' and org_id is null;
