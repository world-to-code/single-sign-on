-- The admission guard reads this table BACKWARDS: given an attribute key an administrator is about to take
-- control of, which policy bindings decide anything by it (PolicyBindingConditionRepository.findByAttrKeyIn).
-- V105 indexed only binding_id and said so in its comment — "only the FK predicate wants an index" — which was
-- true until that reverse lookup existed. It now runs on every attribute mapping and definition write, and
-- without this it is a sequential scan of every condition in the database.
--
-- attr_key alone, not (org_id, attr_key): RLS filters with `org_id = current_org OR org_id IS NULL`, and an OR
-- over the leading column is not something the planner can use an index for. attr_key is the selective
-- predicate; RLS then filters the handful of rows it returns.
CREATE INDEX idx_policy_binding_condition_attr_key ON policy_binding_condition (attr_key);
