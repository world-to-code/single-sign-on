-- Backfill the org membership for admin-created users. UserAdminService.createUser set app_user.org_id but,
-- unlike SCIM and self-signup, never inserted an organization_membership row. So those users belonged to their
-- org by home org_id yet were invisible to every isMember()-based check (e.g. delegating resource admin failed
-- with "must be a member of this org"). Insert the missing HOME-org memberships; a global user (org_id NULL)
-- has no home org and is skipped.
--
-- organization_membership is FORCE ROW LEVEL SECURITY (V42); a migration has no org context, so assert the
-- platform context for this transaction (the policy's platform branch admits the write for every org). The
-- NOT EXISTS keeps it idempotent and re-runnable.
SELECT set_config('app.platform', 'on', true);

INSERT INTO organization_membership (org_id, user_id)
SELECT u.org_id, u.id
FROM app_user u
WHERE u.org_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM organization_membership m
      WHERE m.org_id = u.org_id AND m.user_id = u.id
  );
