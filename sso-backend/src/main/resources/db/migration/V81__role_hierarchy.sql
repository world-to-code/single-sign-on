-- Role-inheritance DAG. A directed edge parent_role_id -> child_role_id means the parent role INHERITS the
-- child role's permission set (transitively). It drives (a) the permission-union computed at login and
-- (b) the dominance predicate that bounds which roles a non-super admin may see/assign. Org-scoped exactly
-- like `role` (V43) and the resource graph (V56): an edge is GLOBAL (org_id NULL) or owned by a tenant.
--
-- The cross-tier seed edge (global ROLE_ADMIN -> a tenant's ROLE_ORG_ADMIN) is stamped with the CHILD's
-- org (the tenant), so it is visible/writable only in that tenant's context and never leaks to other
-- tenants. RLS mirrors the V56 TIGHTENED WITH CHECK: a global (org NULL) edge may be written only from the
-- platform / no-context state, so a tenant context can never mint a global edge.

CREATE TABLE role_hierarchy (
    parent_role_id uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    child_role_id  uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    org_id         uuid REFERENCES organization (id) ON DELETE CASCADE,
    PRIMARY KEY (parent_role_id, child_role_id),
    CONSTRAINT role_hierarchy_no_self_loop CHECK (parent_role_id <> child_role_id)
);

CREATE INDEX idx_role_hierarchy_child ON role_hierarchy (child_role_id);
CREATE INDEX idx_role_hierarchy_org   ON role_hierarchy (org_id);

ALTER TABLE role_hierarchy ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_hierarchy FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON role_hierarchy
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));
