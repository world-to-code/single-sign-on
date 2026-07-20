-- A tenant's user profile: the named unit that groups attribute definitions.
--
-- Until now a tenant had exactly one implicit schema, keyed only on (org_id, entity_kind). That cannot express
-- what an identity source PROVIDES as distinct from what the tenant KEEPS, so a directory's attribute names
-- had nowhere to live and mappings pointed at bare strings. A profile gives both sides a name: the tenant has
-- its own (seeded here, named after the org), and each connector gets one describing the directory it reads.
--
-- Org-scoped only. There is deliberately no platform-tier profile: a profile describes a tenant's people, and
-- a global one would either be dead weight or an inheritance rule nobody asked for.
CREATE TABLE profile (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name                 varchar(120) NOT NULL,
    kind                 varchar(24) NOT NULL,
    -- Set only for a source profile. The lifecycle is the connector's: delete the connector and the profile
    -- describing it goes too, because it describes nothing once the connector is gone.
    connector_id         uuid REFERENCES directory_connector (id) ON DELETE CASCADE,
    -- The tenant's own profile. Not deletable, and always present.
    system               boolean NOT NULL DEFAULT false,
    -- Which profile a manually created user gets. At most one per org.
    default_for_creation boolean NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT profile_kind CHECK (kind IN ('TENANT', 'LDAP', 'SCIM', 'CSV', 'GOOGLE_WORKSPACE', 'ENTRA_ID')),
    -- A source profile must name its connector; a tenant profile must not.
    CONSTRAINT profile_connector_matches_kind CHECK ((kind = 'TENANT') = (connector_id IS NULL)),
    -- Only a tenant profile can be what user creation applies — a directory's schema is not a thing to create
    -- users from.
    CONSTRAINT profile_default_is_tenant CHECK (NOT default_for_creation OR kind = 'TENANT')
);

CREATE UNIQUE INDEX uq_profile_org_name ON profile (org_id, name);
-- At most one default per org, enforced by the database rather than by a read-then-write.
CREATE UNIQUE INDEX uq_profile_default ON profile (org_id) WHERE default_for_creation;
-- Serves the connector's ON DELETE CASCADE, which the (org_id, ...) indexes above cannot.
CREATE INDEX idx_profile_connector ON profile (connector_id) WHERE connector_id IS NOT NULL;

ALTER TABLE profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE profile FORCE ROW LEVEL SECURITY;

-- Strict per-tenant: org_id is NOT NULL, so there is no global row to inherit and no NULL branch to get wrong.
CREATE POLICY profile_org_isolation ON profile
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = coalesce(current_setting('app.current_org', true), '')
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = coalesce(current_setting('app.current_org', true), '')
    );
