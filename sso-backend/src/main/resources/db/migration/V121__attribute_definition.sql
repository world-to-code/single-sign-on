-- The SCHEMA for profile attributes: which keys exist, what they mean, and — the load-bearing part — WHO owns
-- each one. entity_attribute (V93) is a free-form tag store with no notion of a defined key, so two admins can
-- type `dept` and `department` and nothing objects, and nothing can offer a list of keys to choose from.
--
-- Deliberately a CATALOG, not a constraint. Existing attribute rows have no owning definition, and
-- mapping_rule_condition / policy_binding_condition reference attr_key as a bare string with no FK — so
-- deleting a definition must not orphan a live rule. Definitions supply display, typing and ownership;
-- entity_attribute keeps accepting whatever it accepted before. Tightening can come later, deliberately.
--
-- data_type is declared but values stay TEXT in entity_attribute. Typing the storage would ripple through five
-- places that all assume strings: attr_value/attr_values on the two condition tables and their CHECKs, the SQL
-- cohort queries (= / IN / ILIKE), the trigram GIN index, and the in-memory matchers that must agree with the
-- SQL. The type here drives input validation and rendering only.
CREATE TABLE attribute_definition (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid         REFERENCES organization (id) ON DELETE CASCADE,  -- NULL = platform tier
    entity_kind  varchar(16)  NOT NULL,   -- USER | GROUP | APPLICATION | RESOURCE (metadata.EntityKind)
    attr_key     varchar(64)  NOT NULL,   -- same shape entity_attribute accepts
    display_name varchar(120) NOT NULL,
    description  text,
    data_type    varchar(16)  NOT NULL,   -- STRING | INTEGER | BOOLEAN | DATE | ENUM
    enum_values  text[],                  -- ENUM only
    multi_valued boolean      NOT NULL DEFAULT false,
    required     boolean      NOT NULL DEFAULT false,
    -- LOCAL: an administrator owns the value. DIRECTORY: a directory connector owns it — the console shows it
    -- read-only and a sync overwrites it, so "an admin edited it and the sync reverted it" cannot happen.
    source       varchar(16)  NOT NULL DEFAULT 'LOCAL',
    sort_order   int          NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT attribute_definition_data_type
        CHECK (data_type IN ('STRING', 'INTEGER', 'BOOLEAN', 'DATE', 'ENUM')),
    CONSTRAINT attribute_definition_source
        CHECK (source IN ('LOCAL', 'DIRECTORY')),
    -- cardinality (not array_length) so an empty-but-non-null array yields 0 rather than NULL: a NULL CHECK
    -- result passes, which would silently admit an ENUM with no permitted values. Mirrors V102.
    CONSTRAINT attribute_definition_enum_values
        CHECK ((enum_values IS NOT NULL AND cardinality(enum_values) >= 1) = (data_type = 'ENUM'))
);

-- A key is defined once per kind per tier; a tenant may define a key the platform tier also defines.
CREATE UNIQUE INDEX uq_attribute_definition_org
    ON attribute_definition (org_id, entity_kind, attr_key) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX uq_attribute_definition_global
    ON attribute_definition (entity_kind, attr_key) WHERE org_id IS NULL;
-- The editor lists a kind's definitions in display order.
CREATE INDEX idx_attribute_definition_kind ON attribute_definition (org_id, entity_kind, sort_order);

ALTER TABLE attribute_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE attribute_definition FORCE ROW LEVEL SECURITY;
-- STRICT per-tier, like identity_provider (V114) and unlike entity_attribute itself: a definition is a tenant's
-- own profile schema and is NOT inherited from the platform tier, so a tenant must not even read global rows.
CREATE POLICY org_isolation ON attribute_definition
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );
