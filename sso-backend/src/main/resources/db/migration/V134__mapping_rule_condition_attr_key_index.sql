-- The value-grant guard reads this table BACKWARDS: given an attribute key an administrator is about to write,
-- which mapping rules confer a privilege on whoever carries it (MappingRuleConditionRepository.findByAttrKeyIn).
-- That runs on every local USER/GROUP attribute write now, and V101 indexed only rule_id — so the reverse
-- lookup was a sequential scan of every condition in the database.
--
-- This is the exact treatment V133 gave the sibling table policy_binding_condition, left off the mapping side.
-- attr_key alone, not (org_id, attr_key): the RLS predicate filters with `org_id = current_org OR org_id IS
-- NULL`, and an OR over the leading column is not something the planner can use a composite index for. attr_key
-- is the selective predicate; RLS then filters the few rows it returns.
CREATE INDEX idx_mapping_rule_condition_attr_key ON mapping_rule_condition (attr_key);
