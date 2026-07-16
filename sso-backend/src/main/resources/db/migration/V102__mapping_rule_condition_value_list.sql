-- IN operator for a mapping condition: match the key against a LIST of values (an OR over them), so a compound
-- rule can say "department IN (engineering, infrastructure) AND level = senior". The list lives in a text[]
-- column, populated exactly for the IN operator; EQUALS still uses the scalar attr_value and EXISTS neither.
ALTER TABLE mapping_rule_condition ADD COLUMN attr_values text[];

ALTER TABLE mapping_rule_condition DROP CONSTRAINT mapping_rule_condition_op;
ALTER TABLE mapping_rule_condition
    ADD CONSTRAINT mapping_rule_condition_op CHECK (attr_op IN ('EQUALS', 'EXISTS', 'IN'));

-- The value list is present (non-empty) exactly for IN. (The scalar-value shape CHECK from V101 already forces
-- attr_value NULL for a non-EQUALS op, so an IN row carries no scalar value.)
-- cardinality (not array_length) so an empty-but-non-null array yields 0, not NULL — a NULL CHECK result would
-- pass, silently admitting an empty IN list; cardinality('{}') = 0 makes the equality FALSE and rejects it.
ALTER TABLE mapping_rule_condition
    ADD CONSTRAINT mapping_rule_condition_values_shape
        CHECK ((attr_values IS NOT NULL AND cardinality(attr_values) >= 1) = (attr_op = 'IN'));
