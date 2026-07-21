-- Not every identity source is a directory connector.
--
-- V125 said "a source profile names its connector, a tenant profile does not", which was true while LDAP was
-- the only source. SCIM and CSV describe a source too — SCIM pushes to us, CSV is uploaded — but neither has a
-- directory_connector row to point at. The rule is really about the three kinds that ARE connectors.
ALTER TABLE profile DROP CONSTRAINT profile_connector_matches_kind;

ALTER TABLE profile ADD CONSTRAINT profile_connector_matches_kind CHECK (
    CASE
        -- A connector-backed source must name its connector, so the two share a lifecycle.
        WHEN kind IN ('LDAP', 'GOOGLE_WORKSPACE', 'ENTRA_ID') THEN connector_id IS NOT NULL
        -- Everything else — the tenant's own profile, SCIM, CSV — has no connector to name.
        ELSE connector_id IS NULL
    END
);

-- At most one profile per connector-less source kind per organization: a tenant has one SCIM schema and one
-- CSV schema, not a pile of them accumulating on every provisioning run.
CREATE UNIQUE INDEX uq_profile_source_kind
    ON profile (org_id, kind) WHERE connector_id IS NULL AND kind <> 'TENANT';
