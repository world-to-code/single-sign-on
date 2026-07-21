-- Mappings become profile-to-profile.
--
-- Every table touched here is FORCE ROW LEVEL SECURITY, which applies to the owner as well, so without this the
-- INSERTs would be rejected and — worse — the SELECT feeding the carry-over would return nothing right before
-- DROP TABLE discarded the originals.
SET LOCAL app.platform = 'on';

--
-- directory_attribute_mapping tied a mapping to a CONNECTOR and pointed at a bare attr_key string. That could
-- say "this connector's `ou` fills `team`", but not what the connector PROVIDES — there was no source-side
-- schema to name. With a profile on each side, the same statement becomes "the corp-LDAP profile's `ou` fills
-- the octatco.com profile's `team`", and the source side is a schema an administrator can see and edit.

-- Platform-tier connectors are no longer supported: correlation is per-organization, so a global connector
-- could never match anybody. Refuse to continue rather than let DROP TABLE below discard its mappings quietly.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM directory_connector WHERE org_id IS NULL) THEN
        RAISE EXCEPTION 'global directory connectors are no longer supported; remove them before upgrading';
    END IF;
END $$;

-- Every connector needs the profile describing it. Named after the connector so the two read as a pair, and
-- cascading with it, because a schema for a directory nobody reads describes nothing.
-- The connector's display name is unbounded free text and not unique, while profile.name is varchar(120) and
-- unique per organization — so clamp it and disambiguate, exactly as ProfileServiceImpl.uniqueName does.
INSERT INTO profile (org_id, name, kind, connector_id)
SELECT c.org_id,
       CASE WHEN c.rn = 1 THEN c.candidate ELSE left(c.candidate, 110) || ' (' || c.rn || ')' END,
       c.kind, c.id
FROM (
    SELECT dc.org_id, dc.kind, dc.id,
           left(dc.display_name, 120) AS candidate,
           row_number() OVER (PARTITION BY dc.org_id, left(dc.display_name, 120) ORDER BY dc.created_at, dc.id)
               + (SELECT count(*) FROM profile p
                  WHERE p.org_id = dc.org_id AND p.name = left(dc.display_name, 120)) AS rn
    FROM directory_connector dc
    WHERE dc.org_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM profile p WHERE p.connector_id = dc.id)
) c;

CREATE TABLE profile_attribute_mapping (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    source_profile_id uuid        NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    source_attr_key   varchar(64) NOT NULL,
    target_profile_id uuid        NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    target_attr_key   varchar(64) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT profile_mapping_distinct_profiles CHECK (source_profile_id <> target_profile_id)
);

-- The invariants the connector table already held, restated per profile pair: one mapping per source, so two
-- rows never fill different targets from one value; and one origin per target, so a target's value is not
-- order-dependent.
CREATE UNIQUE INDEX uq_profile_mapping_source
    ON profile_attribute_mapping (source_profile_id, source_attr_key);
CREATE UNIQUE INDEX uq_profile_mapping_target
    ON profile_attribute_mapping (target_profile_id, target_attr_key);
-- Serves org_id's ON DELETE CASCADE from organization; no other index here leads with it. The target profile's
-- cascade is already served by uq_profile_mapping_target's leading column.
CREATE INDEX idx_profile_mapping_org ON profile_attribute_mapping (org_id);

-- Carry the existing connector mappings across: source is the connector's new profile, target is the tenant's.
INSERT INTO profile_attribute_mapping (org_id, source_profile_id, source_attr_key, target_profile_id, target_attr_key)
SELECT m.org_id, sp.id, m.source_attribute, tp.id, m.target_key
FROM directory_attribute_mapping m
JOIN profile sp ON sp.connector_id = m.connector_id AND sp.org_id = m.org_id
JOIN profile tp ON tp.org_id = m.org_id AND tp.kind = 'TENANT'
WHERE m.org_id IS NOT NULL;

DROP TABLE directory_attribute_mapping;

ALTER TABLE profile_attribute_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE profile_attribute_mapping FORCE ROW LEVEL SECURITY;

-- org_id is NOT NULL, so there is no global row to inherit and no NULL branch to get wrong.
CREATE POLICY profile_mapping_org_isolation ON profile_attribute_mapping
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = coalesce(current_setting('app.current_org', true), '')
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = coalesce(current_setting('app.current_org', true), '')
    );
