-- Upstream SAML logins become an attribute source of their own, so an assertion's attributes stop having to
-- travel through the OIDC source. That separation is the point: SAML attribute NAMES are chosen entirely by
-- the upstream IdP, so sharing the OIDC source would let a rogue SAML connection name its attributes to match
-- the tenant's OIDC mappings and write values recorded as OIDC-provenance — which attribute-driven role
-- mapping then acts on.
--
-- Connector-less and one per tenant, exactly like OIDC and SCIM: profile_connector_matches_kind already sends
-- anything outside the LDAP/GOOGLE_WORKSPACE/ENTRA_ID list down its ELSE branch (connector_id IS NULL), and
-- uq_profile_source_kind already caps it at one per org — so only the kind whitelist changes.
ALTER TABLE profile DROP CONSTRAINT profile_kind;
ALTER TABLE profile ADD CONSTRAINT profile_kind
    CHECK (kind IN ('TENANT', 'LDAP', 'SCIM', 'CSV', 'GOOGLE_WORKSPACE', 'ENTRA_ID', 'OIDC', 'SAML'));
