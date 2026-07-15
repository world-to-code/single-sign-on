-- Metadata/tag store: a key/value attribute attached to an entity — USER, GROUP, APPLICATION or RESOURCE —
-- org-scoped so a tenant tags only its own entities. The
-- entity is referenced by (kind, id-as-text): user/group/resource ids are uuids stringified, an application id
-- is its client_id / SAML entityId (already a string). Org recipe mirrors V82 (resource_type): tier-aware
-- partial uniqueness (one value per key per tier) + tightened WITH CHECK (a global row only from the platform /
-- no-context state, so a tenant can never mint a global attribute).

CREATE TABLE entity_attribute (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_kind varchar(16)  NOT NULL,   -- USER | GROUP | APPLICATION | RESOURCE
    entity_id   varchar(255) NOT NULL,
    attr_key    varchar(64)  NOT NULL,
    attr_value  varchar(255) NOT NULL,
    org_id      uuid         REFERENCES organization (id) ON DELETE CASCADE,   -- NULL = global (platform)
    created_at  timestamptz  NOT NULL DEFAULT now()
);

-- One value per key per entity, within a tier: a global attribute and a tenant's own may share a name.
CREATE UNIQUE INDEX uq_entity_attribute_global
    ON entity_attribute (entity_kind, entity_id, attr_key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_entity_attribute_org
    ON entity_attribute (org_id, entity_kind, entity_id, attr_key) WHERE org_id IS NOT NULL;
-- Read an entity's attributes; and (Phase 2) find entities by attribute predicate.
CREATE INDEX idx_entity_attribute_entity ON entity_attribute (org_id, entity_kind, entity_id);
CREATE INDEX idx_entity_attribute_lookup ON entity_attribute (org_id, entity_kind, attr_key, attr_value);

ALTER TABLE entity_attribute ENABLE ROW LEVEL SECURITY;
ALTER TABLE entity_attribute FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON entity_attribute
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));
