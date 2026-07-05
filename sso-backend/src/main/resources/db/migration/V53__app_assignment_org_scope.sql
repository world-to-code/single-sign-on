-- Org-scope portal app assignments: an assignment (app -> user/role/group) is owned by the tenant whose
-- admin created it (org_id) or GLOBAL (org_id NULL — e.g. the seeded ROLE_ADMIN -> admin-console grant,
-- which must resolve for the platform super-admin). Resolution (appsForUser / hasAssignment / per-app
-- policy) runs in the launching user's bound tenant, so RLS returns that tenant's assignments plus the
-- global ones; an org assignment never applies to another tenant's users. Admin create stamps the caller's
-- tier and unassign is tier-checked in code (RLS lets any context READ a global row). ON DELETE CASCADE:
-- deleting an organization removes its assignments (its apps are also cascade-deleted), so no orphans
-- survive an org teardown. Mirrors the V47/V52 recipe (global-default + org-override, tighter WITH CHECK).

ALTER TABLE app_assignment ADD COLUMN org_id uuid REFERENCES organization (id) ON DELETE CASCADE;
CREATE INDEX idx_app_assignment_org ON app_assignment (org_id);

-- Make (app, subject) uniqueness per-tier, mirroring the V43/V45/V47 partial-index pattern. The old GLOBAL
-- UNIQUE (app_type, app_id, subject_type, subject_id) is wrong once assignments are org-owned: a USER
-- subject is a GLOBAL identity, so two tenants each granting the same global app to the same shared user
-- would collide on the global key. Split it: uniqueness among GLOBAL assignments, and independently within
-- each org (a global assignment and an org assignment may coexist — the app-layer existsBy, which reads
-- global rows too under RLS, rejects the redundant per-org duplicate with a clean 409).
ALTER TABLE app_assignment
    DROP CONSTRAINT app_assignment_app_type_app_id_subject_type_subject_id_key;
CREATE UNIQUE INDEX uq_app_assignment_global ON app_assignment (app_type, app_id, subject_type, subject_id)
    WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_app_assignment_org ON app_assignment (org_id, app_type, app_id, subject_type, subject_id)
    WHERE org_id IS NOT NULL;

ALTER TABLE app_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON app_assignment
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id IS NULL
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );
