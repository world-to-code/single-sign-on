-- Adds an ATTRIBUTE subject kind to policy_binding: a binding can target the users carrying a metadata
-- predicate (subject_attr_key = subject_attr_value, from entity_attribute V93) instead of a single user/role/
-- group id. The predicate is org-scoped exactly like the rest of the row (RLS + tier-aware uniqueness), so a
-- tenant's ATTRIBUTE binding only ever matches its own users.
--
-- Resolution specificity is now: USER > ATTRIBUTE > ROLE/GROUP > all-subjects — a predicate is a deliberate
-- targeting of a cohort, more specific than a coarse role/group but still an explicit-single-user override.

ALTER TABLE policy_binding ALTER COLUMN subject_type TYPE varchar(16);   -- 'ATTRIBUTE' is 9 chars

ALTER TABLE policy_binding
    ADD COLUMN subject_attr_key   varchar(64),    -- set exactly when subject_type = 'ATTRIBUTE'
    ADD COLUMN subject_attr_value varchar(255);

-- The subject shape becomes three-way: all-subjects (everything null), an id subject (USER/GROUP/ROLE ->
-- subject_id), or an attribute predicate (ATTRIBUTE -> key+value, no subject_id).
ALTER TABLE policy_binding DROP CONSTRAINT policy_binding_subject_shape;
ALTER TABLE policy_binding DROP CONSTRAINT policy_binding_subject_type;
ALTER TABLE policy_binding
    ADD CONSTRAINT policy_binding_subject_type
        CHECK (subject_type IS NULL OR subject_type IN ('USER', 'GROUP', 'ROLE', 'ATTRIBUTE')),
    ADD CONSTRAINT policy_binding_subject_shape
        CHECK ((subject_type IS NULL
                    AND subject_id IS NULL AND subject_attr_key IS NULL AND subject_attr_value IS NULL)
            OR (subject_type IN ('USER', 'GROUP', 'ROLE')
                    AND subject_id IS NOT NULL AND subject_attr_key IS NULL AND subject_attr_value IS NULL)
            OR (subject_type = 'ATTRIBUTE'
                    AND subject_id IS NULL AND subject_attr_key IS NOT NULL AND subject_attr_value IS NOT NULL));

-- One binding per (app, predicate) within a tier — the ATTRIBUTE analogue of uq_policy_binding_*_subject
-- (those key on subject_id, which is NULL for a predicate, so they never enforce this). Split global/org for
-- the same nullable-org reason as the existing indexes.
CREATE UNIQUE INDEX uq_policy_binding_global_attr
    ON policy_binding (app_type, app_id, subject_attr_key, subject_attr_value)
    WHERE org_id IS NULL AND subject_type = 'ATTRIBUTE';
CREATE UNIQUE INDEX uq_policy_binding_org_attr
    ON policy_binding (org_id, app_type, app_id, subject_attr_key, subject_attr_value)
    WHERE org_id IS NOT NULL AND subject_type = 'ATTRIBUTE';

CREATE INDEX idx_policy_binding_attr ON policy_binding (subject_attr_key, subject_attr_value)
    WHERE subject_type = 'ATTRIBUTE';
