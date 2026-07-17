-- Multi-value attributes: an entity may now carry SEVERAL values for one key (a user in two teams, several
-- clearances/tags). The per-(entity, key) uniqueness that forced one value per key is replaced by per-(entity,
-- key, VALUE) uniqueness — multiple values per key are allowed, but a duplicate (entity, key, value) is still
-- forbidden (an idempotent add). Every existing row stays valid (one value per key is a subset of the new
-- constraint), so this is pure DDL with no data change. The matcher already ANY-matches over an entity's values
-- and the cohort reads dedup, so only the storage constraint (and the effective-read fold) had to change.
DROP INDEX uq_entity_attribute_global;
DROP INDEX uq_entity_attribute_org;

CREATE UNIQUE INDEX uq_entity_attribute_global
    ON entity_attribute (entity_kind, entity_id, attr_key, attr_value) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_entity_attribute_org
    ON entity_attribute (org_id, entity_kind, entity_id, attr_key, attr_value) WHERE org_id IS NOT NULL;
