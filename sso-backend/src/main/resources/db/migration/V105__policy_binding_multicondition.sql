-- Multi-condition AND for policy bindings: an ATTRIBUTE binding matches a user only when they satisfy ALL of its
-- conditions (e.g. "dept = eng AND level = senior AND clearance EXISTS"). The single inline predicate columns on
-- policy_binding move to a child table, one row per condition, so a binding can carry several. Each condition keeps
-- the policy-TARGETABLE operator set (EQUALS/NOT_EQUALS/EXISTS/NOT_EXISTS/CONTAINS — no IN, which needs value-list
-- storage the target lacks). Org-scoped + RLS exactly like the parent binding; binding_id is a FK (ON DELETE
-- CASCADE clears a deleted binding's conditions). Mirrors mapping_rule_condition (V101).
CREATE TABLE policy_binding_condition (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    binding_id uuid         NOT NULL REFERENCES policy_binding (id) ON DELETE CASCADE,
    attr_key   varchar(64)  NOT NULL,
    attr_op    varchar(16)  NOT NULL,
    attr_value varchar(255),
    org_id     uuid         REFERENCES organization (id) ON DELETE CASCADE,   -- NULL = global (platform)
    created_at timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT policy_binding_condition_op
        CHECK (attr_op IN ('EQUALS', 'NOT_EQUALS', 'EXISTS', 'NOT_EXISTS', 'CONTAINS')),
    CONSTRAINT policy_binding_condition_value_shape
        CHECK ((attr_value IS NOT NULL) = (attr_op IN ('EQUALS', 'NOT_EQUALS', 'CONTAINS')))
);

-- Conditions are read by their binding (findByBindingId) or all-at-once for the candidate set (findByBindingIdIn);
-- the user's attributes come from entity_attribute, not this table, so only the FK predicate wants an index.
CREATE INDEX idx_policy_binding_condition_binding ON policy_binding_condition (binding_id);

ALTER TABLE policy_binding_condition ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy_binding_condition FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON policy_binding_condition
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));

-- Carry every existing single-predicate ATTRIBUTE binding over as a one-condition binding (behavior-preserving).
-- A migration has no org context and both tables FORCE row-level security, so without the platform assertion the
-- RLS USING clause would hide every TENANT binding from the SELECT (and WITH CHECK reject its INSERT) — only
-- global bindings would carry over, leaving tenant bindings condition-less (→ they match nobody → the resolver
-- fails them closed and the tenant silently loses those targeted policies). Assert the platform context first so
-- the policy admits every org's rows, exactly as V92's/V101's backfill does.
SELECT set_config('app.platform', 'on', true);
INSERT INTO policy_binding_condition (binding_id, attr_key, attr_op, attr_value, org_id)
    SELECT id, subject_attr_key, subject_attr_op, subject_attr_value, org_id
    FROM policy_binding WHERE subject_type = 'ATTRIBUTE';

-- Drop the now-migrated inline predicate columns and every identity object keyed on them. A binding's identity is
-- now its SET of conditions, which a simple UNIQUE cannot express, so the per-tier one-binding-per-predicate guard
-- is dropped — a duplicate compound binding is resolution-harmless (same conditions → same specificity), and the
-- writers reconcile by finding a binding whose condition set equals the wanted group before creating a new one.
DROP INDEX uq_policy_binding_global_attr;
DROP INDEX uq_policy_binding_org_attr;
DROP INDEX idx_policy_binding_attr;
ALTER TABLE policy_binding DROP CONSTRAINT policy_binding_subject_shape;
ALTER TABLE policy_binding DROP COLUMN subject_attr_key;
ALTER TABLE policy_binding DROP COLUMN subject_attr_op;
ALTER TABLE policy_binding DROP COLUMN subject_attr_value;

-- The subject shape is now two-branched: an id-subject (USER/GROUP/ROLE) carries a subject_id; an ATTRIBUTE
-- binding carries neither (its predicate lives in policy_binding_condition); all-subjects carries nothing.
ALTER TABLE policy_binding
    ADD CONSTRAINT policy_binding_subject_shape
        CHECK ((subject_type IS NULL AND subject_id IS NULL)
            OR (subject_type IN ('USER', 'GROUP', 'ROLE') AND subject_id IS NOT NULL)
            OR (subject_type = 'ATTRIBUTE' AND subject_id IS NULL));
