-- Upstream OIDC logins become a first-class attribute source. A federated login can now carry claims
-- (given_name, family_name, …) onto a user's attributes the same way a directory sync or SCIM push does.
--
-- OIDC is a CONNECTOR-LESS source, one per tenant (like SCIM): the profile_connector_matches_kind CHECK
-- already sends anything outside the LDAP/GOOGLE_WORKSPACE/ENTRA_ID list down its ELSE branch (connector_id
-- IS NULL), and uq_profile_source_kind already caps it at one per org — so only the kind whitelist changes.
ALTER TABLE profile DROP CONSTRAINT profile_kind;
ALTER TABLE profile ADD CONSTRAINT profile_kind
    CHECK (kind IN ('TENANT', 'LDAP', 'SCIM', 'CSV', 'GOOGLE_WORKSPACE', 'ENTRA_ID', 'OIDC'));

-- Who configured a provider, so the attributes its logins fill are attributable to an administrator — the
-- federation twin of directory_connector.configured_by. Nullable: a provider seeded by a test or created
-- before this column has nobody on record, which the provenance guard reads as "unattributed", not "nobody".
ALTER TABLE identity_provider ADD COLUMN configured_by uuid;
