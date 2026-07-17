-- IN operator for a policy-binding condition: match the key against a LIST of values (an OR over them), so a
-- compound target can say "department IN (engineering, infrastructure) AND level = senior". The list lives in a
-- text[] column, populated exactly for the IN operator; the scalar operators still use attr_value and the key
-- operators neither. Mirrors mapping_rule_condition (V102) — IN is now targetable for policy bindings too.
ALTER TABLE policy_binding_condition ADD COLUMN attr_values text[];

ALTER TABLE policy_binding_condition DROP CONSTRAINT policy_binding_condition_op;
ALTER TABLE policy_binding_condition
    ADD CONSTRAINT policy_binding_condition_op
        CHECK (attr_op IN ('EQUALS', 'NOT_EQUALS', 'EXISTS', 'NOT_EXISTS', 'CONTAINS', 'IN'));

-- The value list is present (non-empty) exactly for IN. The scalar-value shape CHECK (V105) already forces
-- attr_value NULL for a non-scalar op, so an IN row carries no scalar value.
-- cardinality (not array_length) so an empty-but-non-null array yields 0, not NULL — a NULL CHECK result would
-- pass, silently admitting an empty IN list; cardinality('{}') = 0 makes the equality FALSE and rejects it.
ALTER TABLE policy_binding_condition
    ADD CONSTRAINT policy_binding_condition_values_shape
        CHECK ((attr_values IS NOT NULL AND cardinality(attr_values) >= 1) = (attr_op = 'IN'));
