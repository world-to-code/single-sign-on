-- An index on group_role.role_id.
--
-- The table's primary key is (group_id, role_id), which serves any predicate leading with group_id — the bulk
-- delegation read does exactly that. Nothing serves the TRAILING column, and two things scan on it: the
-- ON DELETE CASCADE from role(id), and deleteByRoleId when a role is retired. Both then sequentially scan
-- group_role, which the project's own db-invariants rule ("every FK gets an index") exists to prevent.
--
-- Not a correctness fix and not urgent at current sizes; it is the missing half of a foreign key that has been
-- declared since V29.
CREATE INDEX IF NOT EXISTS idx_group_role_role ON group_role (role_id);
