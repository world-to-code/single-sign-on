-- Fold the login-time sign-on policy into policy_binding (V83) as PORTAL/user AUTH bindings, retiring the
-- last legacy source: auth_policy.applies_to_login (the "used for login" flag) + auth_policy_user /
-- auth_policy_role (the login assignment join tables). After this, LoginPolicyResolver resolves login purely
-- from the matrix (PORTAL/user), and AuthPolicyResolver.resolveForUser is gone.
--
-- MODEL NOTE (intended semantic change — NOT a pure behaviour-preservation): the matrix keeps exactly ONE
-- binding per (app, subject, tenant) — the partial unique indexes uq_policy_binding_*_all / *_subject. So a
-- subject (or the all-subjects slot) maps to a single login policy per org. Two consequences:
--   (1) SAME-SLOT dedup (preserved): the same user could sit in several login policies, or an org could have
--       several "global" login policies, but only the highest-priority ENABLED one ever resolved. Each
--       DISTINCT ON below keeps exactly that resolution winner (enabled DESC, priority DESC, id) per slot and
--       drops the shadowed duplicates — no resolution change, only the dead admin-view duplicates disappear.
--   (2) CROSS-SPECIFICITY resolution CHANGES: the pre-matrix engine (AuthPolicyResolverImpl.resolveForUser)
--       picked the max-priority policy across ALL of a user's candidates regardless of how they were targeted,
--       so a broad high-priority policy beat a narrow low-priority one. The matrix resolver is specificity-first
--       (USER > ROLE > all-subjects, THEN priority — same rule V87 adopted for per-app auth), so a MORE SPECIFIC
--       binding now wins even at a LOWER priority. Where an admin used priority (not targeting) to make a broad
--       STRONGER login policy outrank a narrow WEAKER one, the narrower policy now governs — which can RAISE or
--       LOWER the required login factors. This is the intended unification, but it is a real behaviour change:
--       audit deployments with overlapping broad-vs-narrow login policies before applying (a per-(org,user)
--       scan of who wins under each rule) if silent factor changes at login are a concern.
--
-- policy_binding and auth_policy are FORCE ROW LEVEL SECURITY: without the platform GUC a non-superuser owner
-- sees only GLOBAL rows and would skip every tenant's policy/binding in the backfill (org_isolation honours
-- this GUC — V46/V83). The join tables have no RLS.
SET LOCAL app.platform = 'on';

-- (1) per-user login assignment -> PORTAL/user USER binding, in the policy's own tier. DISTINCT ON keeps one
--     binding per (org, user): the resolution winner among the policies that claim this user for login.
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, auth_policy_id, priority, org_id)
SELECT DISTINCT ON (ap.org_id, apu.user_id)
       'PORTAL', 'user', 'USER', apu.user_id, ap.id, ap.priority, ap.org_id
FROM auth_policy_user apu
JOIN auth_policy ap ON ap.id = apu.policy_id
WHERE ap.applies_to_login = true
ORDER BY ap.org_id, apu.user_id, ap.enabled DESC, ap.priority DESC, ap.id;

-- (2) per-role login assignment -> PORTAL/user ROLE binding, one per (org, role).
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, auth_policy_id, priority, org_id)
SELECT DISTINCT ON (ap.org_id, apr.role_id)
       'PORTAL', 'user', 'ROLE', apr.role_id, ap.id, ap.priority, ap.org_id
FROM auth_policy_role apr
JOIN auth_policy ap ON ap.id = apr.policy_id
WHERE ap.applies_to_login = true
ORDER BY ap.org_id, apr.role_id, ap.enabled DESC, ap.priority DESC, ap.id;

-- (3) "global" login policy (applies_to_login, no user/role assignment) -> the org's all-subjects PORTAL/user
--     AUTH binding, one per org (the resolution winner). An all-subjects PORTAL/user row may already exist as a
--     user-portal SESSION binding (V84) — the auth policy must attach to THAT row, not a second row (the
--     all-subjects slot is unique per org). So update an existing row, else insert a new one.
WITH global_login AS (
    SELECT DISTINCT ON (ap.org_id) ap.org_id, ap.id AS policy_id, ap.priority
    FROM auth_policy ap
    WHERE ap.applies_to_login = true
      AND NOT EXISTS (SELECT 1 FROM auth_policy_user u WHERE u.policy_id = ap.id)
      AND NOT EXISTS (SELECT 1 FROM auth_policy_role r WHERE r.policy_id = ap.id)
    ORDER BY ap.org_id, ap.enabled DESC, ap.priority DESC, ap.id
)
UPDATE policy_binding pb
SET auth_policy_id = gl.policy_id
FROM global_login gl
WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type IS NULL
  AND pb.org_id IS NOT DISTINCT FROM gl.org_id
  AND pb.auth_policy_id IS NULL;

WITH global_login AS (
    SELECT DISTINCT ON (ap.org_id) ap.org_id, ap.id AS policy_id, ap.priority
    FROM auth_policy ap
    WHERE ap.applies_to_login = true
      AND NOT EXISTS (SELECT 1 FROM auth_policy_user u WHERE u.policy_id = ap.id)
      AND NOT EXISTS (SELECT 1 FROM auth_policy_role r WHERE r.policy_id = ap.id)
    ORDER BY ap.org_id, ap.enabled DESC, ap.priority DESC, ap.id
)
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, auth_policy_id, priority, org_id)
SELECT 'PORTAL', 'user', NULL, NULL, gl.policy_id, gl.priority, gl.org_id
FROM global_login gl
WHERE NOT EXISTS (
    SELECT 1 FROM policy_binding pb
    WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type IS NULL
      AND pb.org_id IS NOT DISTINCT FROM gl.org_id
);

-- The legacy sources are now fully represented in the matrix.
DROP TABLE auth_policy_user;
DROP TABLE auth_policy_role;
ALTER TABLE auth_policy DROP COLUMN applies_to_login;
