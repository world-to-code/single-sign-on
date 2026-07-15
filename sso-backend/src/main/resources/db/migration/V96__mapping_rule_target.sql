-- Generalize a mapping rule's target beyond a group: rename group_id -> target_id (the id of the group OR role
-- the rule assigns) and widen the kind. Postgres propagates the rename to the indexes/constraints that
-- referenced the column. RESOURCE_MEMBER is a later kind, not yet in the CHECK.
ALTER TABLE mapping_rule RENAME COLUMN group_id TO target_id;
ALTER TABLE mapping_rule_membership RENAME COLUMN group_id TO target_id;

ALTER TABLE mapping_rule DROP CONSTRAINT mapping_rule_then_kind;
ALTER TABLE mapping_rule ADD CONSTRAINT mapping_rule_then_kind CHECK (then_kind IN ('GROUP', 'ROLE'));

ALTER INDEX idx_mapping_rule_group RENAME TO idx_mapping_rule_target;
ALTER INDEX idx_mapping_rule_membership_claim RENAME TO idx_mapping_rule_membership_target;
