-- Tighten the WITH CHECK on the three EARLIEST org-scoped tables (role, user_group, auth_policy) to match
-- the recipe every later table already uses (V47/V48/V51/V52/V53). Their original policies (V43/V45/V46)
-- allowed a GLOBAL (org_id IS NULL) write from ANY context — including an org-bound one — whereas the newer
-- form only permits a global write from the platform / no-context state:
--     org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''
-- No app path can currently reach the loose branch (every create() stamps org_id from OrgContext, and global
-- rows are only seeded in the platform/no-context state), so this changes no runtime behavior. But now that
-- the app runs as a NON-SUPERUSER and RLS is the real backstop (see V54), the `role` table is the most
-- sensitive of all — a global role's NAME becomes a granted authority — so its DB guard must not be the
-- loosest. USING is unchanged (global rows stay readable in every context); only WITH CHECK is narrowed.

DROP POLICY org_isolation ON role;
CREATE POLICY org_isolation ON role
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

DROP POLICY org_isolation ON user_group;
CREATE POLICY org_isolation ON user_group
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

DROP POLICY org_isolation ON auth_policy;
CREATE POLICY org_isolation ON auth_policy
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
