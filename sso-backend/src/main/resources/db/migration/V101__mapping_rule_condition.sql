-- Multi-condition mapping rules: a rule matches users satisfying ALL of its conditions (AND). The single
-- predicate columns on mapping_rule move to a child table, one row per condition, so a rule can carry several.
-- Each condition keeps the positive-operator model (EQUALS with a value, EXISTS value-less). Org-scoped + RLS
-- exactly like the parent rule. rule_id is a same-module FK (ON DELETE CASCADE clears a deleted rule's conditions).
CREATE TABLE mapping_rule_condition (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id    uuid         NOT NULL REFERENCES mapping_rule (id) ON DELETE CASCADE,
    attr_key   varchar(64)  NOT NULL,
    attr_op    varchar(16)  NOT NULL,
    attr_value varchar(255),
    org_id     uuid         REFERENCES organization (id) ON DELETE CASCADE,   -- NULL = global (platform)
    created_at timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT mapping_rule_condition_op CHECK (attr_op IN ('EQUALS', 'EXISTS')),
    CONSTRAINT mapping_rule_condition_value_shape CHECK ((attr_value IS NOT NULL) = (attr_op = 'EQUALS'))
);

-- Conditions are only ever read by their rule (findByRuleId) or all-at-once (reevaluateUser); the cohort itself
-- comes from entity_attribute, not this table — so a (key,value) index here would only add write cost.
CREATE INDEX idx_mapping_rule_condition_rule ON mapping_rule_condition (rule_id);

ALTER TABLE mapping_rule_condition ENABLE ROW LEVEL SECURITY;
ALTER TABLE mapping_rule_condition FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON mapping_rule_condition
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));

-- Carry every existing single-predicate rule over as a one-condition rule (behavior-preserving). A migration has
-- no org context and both tables FORCE row-level security, so without the platform assertion the RLS USING clause
-- would hide every TENANT rule from the SELECT (and WITH CHECK reject its INSERT) — only global rules would carry
-- over, leaving tenant rules condition-less (→ they match nobody → auto-assignments get retracted). Assert the
-- platform context first so the policy admits every org's rows, exactly as V92's backfill does.
SELECT set_config('app.platform', 'on', true);
INSERT INTO mapping_rule_condition (rule_id, attr_key, attr_op, attr_value, org_id)
    SELECT id, attr_key, attr_op, attr_value, org_id FROM mapping_rule;

-- Drop the now-migrated single-predicate columns from mapping_rule (and the constraints/indexes keyed on them).
-- A rule's identity is now its SET of conditions + target, which a simple UNIQUE cannot express, so the
-- per-tier duplicate-rule guard is dropped — a duplicate compound rule is harmless (provenance dedups members).
DROP INDEX uq_mapping_rule_global;
DROP INDEX uq_mapping_rule_org;
DROP INDEX idx_mapping_rule_lookup;
ALTER TABLE mapping_rule DROP CONSTRAINT mapping_rule_attr_op;
ALTER TABLE mapping_rule DROP CONSTRAINT mapping_rule_attr_value_shape;
ALTER TABLE mapping_rule DROP COLUMN attr_key;
ALTER TABLE mapping_rule DROP COLUMN attr_op;
ALTER TABLE mapping_rule DROP COLUMN attr_value;
