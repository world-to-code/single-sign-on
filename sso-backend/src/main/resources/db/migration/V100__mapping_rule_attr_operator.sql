-- Richer ABAC operators for an auto-mapping rule's predicate. A mapping rule accepts only the POSITIVE,
-- index-able operators — EQUALS (today) and EXISTS (key present, any value): a NOT_* rule would materialize
-- "every user WITHOUT the attribute", an un-indexable, ever-growing cohort and an auto-mapping anti-pattern, so
-- it is rejected at the API. Existing rules backfill to EQUALS (behavior unchanged). attr_value becomes NULL for
-- an EXISTS rule; the added-with-default keeps the fill of existing rows a metadata-only change.
ALTER TABLE mapping_rule ADD COLUMN attr_op varchar(16) NOT NULL DEFAULT 'EQUALS';
ALTER TABLE mapping_rule ALTER COLUMN attr_op DROP DEFAULT;   -- a new rule must state its operator explicitly

ALTER TABLE mapping_rule ADD CONSTRAINT mapping_rule_attr_op CHECK (attr_op IN ('EQUALS', 'EXISTS'));

-- The value is present exactly for the EQUALS operator (EXISTS is value-less).
ALTER TABLE mapping_rule ALTER COLUMN attr_value DROP NOT NULL;
ALTER TABLE mapping_rule
    ADD CONSTRAINT mapping_rule_attr_value_shape CHECK ((attr_value IS NOT NULL) = (attr_op = 'EQUALS'));

-- No duplicate identical rule within a tier now includes the operator, deduping value-less rules via coalesce.
DROP INDEX uq_mapping_rule_global;
DROP INDEX uq_mapping_rule_org;
CREATE UNIQUE INDEX uq_mapping_rule_global
    ON mapping_rule (attr_key, attr_op, coalesce(attr_value, ''), then_kind, target_id) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_mapping_rule_org
    ON mapping_rule (org_id, attr_key, attr_op, coalesce(attr_value, ''), then_kind, target_id)
    WHERE org_id IS NOT NULL;
