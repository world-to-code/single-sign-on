-- Row-Level Security on organization_membership — the first org-scoped table.
--
-- Isolation is enforced at the DB engine, driven by two per-connection GUCs set by OrgAwareDataSource:
--   app.current_org = the active organization id (org-bound context), or unset/empty
--   app.platform    = 'on' for the cross-org platform context (super-admin, seeder, background jobs)
--
-- FORCE is required because the application connects as the table OWNER (role `sso`), which would
-- otherwise bypass RLS. The predicate compares org_id AS TEXT to the GUC so an unset ('' / NULL) context
-- matches no row (fail-closed) without an invalid-uuid cast error. WITH CHECK mirrors USING, so a write
-- must run in the platform context OR in the exact org it targets.

ALTER TABLE organization_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_membership FORCE ROW LEVEL SECURITY;

CREATE POLICY org_isolation ON organization_membership
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    );
