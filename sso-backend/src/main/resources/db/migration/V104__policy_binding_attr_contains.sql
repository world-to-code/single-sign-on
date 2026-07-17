-- Allow a policy binding's ATTRIBUTE subject to use the CONTAINS operator (case-insensitive substring), matching
-- what mapping conditions already support. CONTAINS is a value operator (it carries the substring in the existing
-- subject_attr_value), so only the shape CHECK widens — no new column and no resolver change (the resolver already
-- matches CONTAINS in memory). IN stays mapping-only (it needs value-list storage the policy binding lacks).
ALTER TABLE policy_binding DROP CONSTRAINT policy_binding_subject_shape;
ALTER TABLE policy_binding
    ADD CONSTRAINT policy_binding_subject_shape
        CHECK ((subject_type IS NULL
                    AND subject_id IS NULL AND subject_attr_key IS NULL AND subject_attr_value IS NULL
                    AND subject_attr_op IS NULL)
            OR (subject_type IN ('USER', 'GROUP', 'ROLE')
                    AND subject_id IS NOT NULL AND subject_attr_key IS NULL AND subject_attr_value IS NULL
                    AND subject_attr_op IS NULL)
            OR (subject_type = 'ATTRIBUTE'
                    AND subject_id IS NULL AND subject_attr_key IS NOT NULL
                    AND subject_attr_op IN ('EQUALS', 'NOT_EQUALS', 'EXISTS', 'NOT_EXISTS', 'CONTAINS')
                    AND (subject_attr_value IS NOT NULL)
                        = (subject_attr_op IN ('EQUALS', 'NOT_EQUALS', 'CONTAINS'))));
