-- Richer ABAC operators for a policy binding's ATTRIBUTE subject: alongside the stored key/value, an operator
-- decides HOW the user's attribute is tested — EQUALS (today), NOT_EQUALS (exclusion), EXISTS / NOT_EXISTS
-- (key presence, value-less). Only the operator column is added; value-list (IN) operators are a later change.
-- Existing ATTRIBUTE bindings backfill to EQUALS so resolution is unchanged.
ALTER TABLE policy_binding ADD COLUMN subject_attr_op varchar(16);
UPDATE policy_binding SET subject_attr_op = 'EQUALS' WHERE subject_type = 'ATTRIBUTE';

-- The subject shape becomes: all-subjects (everything null), an id subject (USER/GROUP/ROLE -> subject_id), or
-- an attribute predicate (ATTRIBUTE -> key + op, value present exactly for the value operators EQUALS/NOT_EQUALS
-- and NULL for the key operators EXISTS/NOT_EXISTS). subject_attr_op is set exactly for an ATTRIBUTE row.
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
                    AND subject_attr_op IN ('EQUALS', 'NOT_EQUALS', 'EXISTS', 'NOT_EXISTS')
                    AND (subject_attr_value IS NOT NULL) = (subject_attr_op IN ('EQUALS', 'NOT_EQUALS'))));

-- One binding per (app, predicate) within a tier now keys on the operator too, and dedups the value-less
-- operators via coalesce (two EXISTS bindings on the same key are the same predicate, both with a NULL value).
DROP INDEX uq_policy_binding_global_attr;
DROP INDEX uq_policy_binding_org_attr;
CREATE UNIQUE INDEX uq_policy_binding_global_attr
    ON policy_binding (app_type, app_id, subject_attr_key, subject_attr_op, coalesce(subject_attr_value, ''))
    WHERE org_id IS NULL AND subject_type = 'ATTRIBUTE';
CREATE UNIQUE INDEX uq_policy_binding_org_attr
    ON policy_binding (org_id, app_type, app_id, subject_attr_key, subject_attr_op, coalesce(subject_attr_value, ''))
    WHERE org_id IS NOT NULL AND subject_type = 'ATTRIBUTE';
