-- CONTAINS operator for a mapping condition: a case-insensitive substring match on an attribute value
-- ("department CONTAINS eng"). A plain ILIKE '%x%' is a sequential scan, so back it with a pg_trgm GIN index on
-- entity_attribute.attr_value — trigram indexes accelerate ILIKE/LIKE '%…%'. pg_trgm is a TRUSTED extension
-- (Postgres 13+), so the schema owner can install it without superuser; if a locked-down deployment forbids
-- even that, a DBA pre-creates the extension and this statement is then a no-op.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_entity_attribute_value_trgm ON entity_attribute USING gin (attr_value gin_trgm_ops);

-- CONTAINS reuses the scalar attr_value (the substring), so widen the operator whitelist and the value-shape
-- CHECK to treat CONTAINS like EQUALS (a value is present); EXISTS/IN still carry no scalar value.
ALTER TABLE mapping_rule_condition DROP CONSTRAINT mapping_rule_condition_op;
ALTER TABLE mapping_rule_condition
    ADD CONSTRAINT mapping_rule_condition_op CHECK (attr_op IN ('EQUALS', 'EXISTS', 'IN', 'CONTAINS'));

ALTER TABLE mapping_rule_condition DROP CONSTRAINT mapping_rule_condition_value_shape;
ALTER TABLE mapping_rule_condition
    ADD CONSTRAINT mapping_rule_condition_value_shape
        CHECK ((attr_value IS NOT NULL) = (attr_op IN ('EQUALS', 'CONTAINS')));
