-- Give every existing tenant a PORTAL/admin session binding pointing at its OWN "Default" policy.
--
-- WHY: tenant baseline provisioning created the org's Default policy and bound it — but only to PORTAL/user,
-- because the binding is written as the policy's assignment scope (SessionBindingsImpl is hard-wired to
-- PortalApps.USER). Nothing ever bound PORTAL/admin, so ConsoleSessionPolicyImpl found no binding in the
-- acting org and fell back to the GLOBAL one: every tenant's admin console governed its sensitive-action
-- step-up by the PLATFORM Default (a 2-minute window) no matter what the tenant configured. The provisioner
-- now binds both; this backfills the tenants created before it did.
--
-- Only tenants that have NOT already chosen a console policy are touched, so an explicit selection is kept.

-- policy_binding and session_policy are FORCE ROW LEVEL SECURITY: a non-superuser Flyway owner sees only
-- GLOBAL rows, which would silently skip every tenant. Testcontainers migrates as a superuser, so no test
-- can catch its absence — see the V77 backfill incident.
SET LOCAL app.platform = 'on';

INSERT INTO policy_binding (id, app_type, app_id, subject_type, subject_id,
                            session_policy_id, priority, session_priority, org_id)
SELECT gen_random_uuid(), 'PORTAL', 'admin', NULL, NULL, sp.id, 0, 0, sp.org_id
FROM session_policy sp
WHERE sp.org_id IS NOT NULL
  AND sp.name = 'Default'
  AND NOT EXISTS (
        SELECT 1 FROM policy_binding pb
        WHERE pb.org_id = sp.org_id
          AND pb.app_type = 'PORTAL'
          AND pb.app_id = 'admin'
          AND pb.subject_type IS NULL);
