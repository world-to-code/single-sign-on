-- Fold the per-user SESSION policy assignments into policy_binding (V83) as PORTAL/user SESSION bindings,
-- retiring the legacy session_policy_user / session_policy_role join tables (V21). After this, the per-user
-- session policy resolves purely from the matrix (UserSessionPolicy -> resolveSessionPolicy(PORTAL/user)),
-- and SessionPolicyServiceImpl no longer keys resolution on assignment rows. The IP-rule child rows
-- (session_policy_ip_rule) are policy config, not assignments, and STAY on the policy.
--
-- MODEL NOTE (intended semantic change — NOT a pure behaviour-preservation), mirroring V88 for the auth axis:
--   (1) SAME-SLOT dedup (preserved): only the highest-priority ENABLED policy per subject slot ever resolved;
--       each DISTINCT ON keeps exactly that winner (enabled DESC, priority DESC, id) and drops the shadowed
--       duplicates — no resolution change, only dead admin-view duplicates disappear.
--   (2) CROSS-SPECIFICITY resolution CHANGES: the pre-matrix engine picked the max-priority policy across ALL
--       of a user's candidates; the matrix resolver is specificity-first (USER > ROLE > all-subjects, THEN
--       priority), so a MORE SPECIFIC binding now wins even at a LOWER priority — which can change a user's
--       session lifetime / re-auth cadence. Audit deployments with overlapping broad-vs-narrow session policies
--       before applying if silent session-governance changes are a concern.
--
-- A PORTAL/user row may ALREADY exist as a LOGIN (auth) binding from V88 (same (app, subject, org) slot is one
-- row), so every backfill below is UPDATE-existing-else-INSERT: it attaches session_policy_id/session_priority
-- to a co-located auth row rather than violating the partial unique indexes.
--
-- policy_binding and session_policy are FORCE ROW LEVEL SECURITY: without the platform GUC a non-superuser
-- owner sees only GLOBAL rows and would skip every tenant's policy/binding (org_isolation honours it — V47/V83).
SET LOCAL app.platform = 'on';

-- The SESSION tie-break weight, independent of the AUTH `priority` on the same row: a co-located row's auth and
-- session policies are assigned separately and keep their own weights (PolicyBindingResolverImpl sorts each
-- field on its own priority column).
ALTER TABLE policy_binding ADD COLUMN session_priority int NOT NULL DEFAULT 0;

-- (1) per-user assignment -> PORTAL/user USER session binding, one per (org, user): the resolution winner.
WITH per_user AS (
    SELECT DISTINCT ON (sp.org_id, spu.user_id) sp.org_id, spu.user_id, sp.id AS policy_id, sp.priority
    FROM session_policy_user spu
    JOIN session_policy sp ON sp.id = spu.policy_id
    ORDER BY sp.org_id, spu.user_id, sp.enabled DESC, sp.priority DESC, sp.id
)
UPDATE policy_binding pb
SET session_policy_id = pu.policy_id, session_priority = pu.priority
FROM per_user pu
WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type = 'USER'
  AND pb.subject_id = pu.user_id AND pb.org_id IS NOT DISTINCT FROM pu.org_id
  AND pb.session_policy_id IS NULL;

WITH per_user AS (
    SELECT DISTINCT ON (sp.org_id, spu.user_id) sp.org_id, spu.user_id, sp.id AS policy_id, sp.priority
    FROM session_policy_user spu
    JOIN session_policy sp ON sp.id = spu.policy_id
    ORDER BY sp.org_id, spu.user_id, sp.enabled DESC, sp.priority DESC, sp.id
)
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, session_policy_id, session_priority, org_id)
SELECT 'PORTAL', 'user', 'USER', pu.user_id, pu.policy_id, pu.priority, pu.org_id
FROM per_user pu
WHERE NOT EXISTS (
    SELECT 1 FROM policy_binding pb
    WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type = 'USER'
      AND pb.subject_id = pu.user_id AND pb.org_id IS NOT DISTINCT FROM pu.org_id);

-- (2) per-role assignment -> PORTAL/user ROLE session binding, one per (org, role).
WITH per_role AS (
    SELECT DISTINCT ON (sp.org_id, spr.role_id) sp.org_id, spr.role_id, sp.id AS policy_id, sp.priority
    FROM session_policy_role spr
    JOIN session_policy sp ON sp.id = spr.policy_id
    ORDER BY sp.org_id, spr.role_id, sp.enabled DESC, sp.priority DESC, sp.id
)
UPDATE policy_binding pb
SET session_policy_id = pr.policy_id, session_priority = pr.priority
FROM per_role pr
WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type = 'ROLE'
  AND pb.subject_id = pr.role_id AND pb.org_id IS NOT DISTINCT FROM pr.org_id
  AND pb.session_policy_id IS NULL;

WITH per_role AS (
    SELECT DISTINCT ON (sp.org_id, spr.role_id) sp.org_id, spr.role_id, sp.id AS policy_id, sp.priority
    FROM session_policy_role spr
    JOIN session_policy sp ON sp.id = spr.policy_id
    ORDER BY sp.org_id, spr.role_id, sp.enabled DESC, sp.priority DESC, sp.id
)
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, session_policy_id, session_priority, org_id)
SELECT 'PORTAL', 'user', 'ROLE', pr.role_id, pr.policy_id, pr.priority, pr.org_id
FROM per_role pr
WHERE NOT EXISTS (
    SELECT 1 FROM policy_binding pb
    WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type = 'ROLE'
      AND pb.subject_id = pr.role_id AND pb.org_id IS NOT DISTINCT FROM pr.org_id);

-- (3) "global" session policy (no user/role assignment) -> the org's all-subjects PORTAL/user SESSION binding.
WITH global_session AS (
    SELECT DISTINCT ON (sp.org_id) sp.org_id, sp.id AS policy_id, sp.priority
    FROM session_policy sp
    WHERE NOT EXISTS (SELECT 1 FROM session_policy_user u WHERE u.policy_id = sp.id)
      AND NOT EXISTS (SELECT 1 FROM session_policy_role r WHERE r.policy_id = sp.id)
    ORDER BY sp.org_id, sp.enabled DESC, sp.priority DESC, sp.id
)
UPDATE policy_binding pb
SET session_policy_id = gs.policy_id, session_priority = gs.priority
FROM global_session gs
WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type IS NULL
  AND pb.org_id IS NOT DISTINCT FROM gs.org_id
  AND pb.session_policy_id IS NULL;

WITH global_session AS (
    SELECT DISTINCT ON (sp.org_id) sp.org_id, sp.id AS policy_id, sp.priority
    FROM session_policy sp
    WHERE NOT EXISTS (SELECT 1 FROM session_policy_user u WHERE u.policy_id = sp.id)
      AND NOT EXISTS (SELECT 1 FROM session_policy_role r WHERE r.policy_id = sp.id)
    ORDER BY sp.org_id, sp.enabled DESC, sp.priority DESC, sp.id
)
INSERT INTO policy_binding (app_type, app_id, subject_type, subject_id, session_policy_id, session_priority, org_id)
SELECT 'PORTAL', 'user', NULL, NULL, gs.policy_id, gs.priority, gs.org_id
FROM global_session gs
WHERE NOT EXISTS (
    SELECT 1 FROM policy_binding pb
    WHERE pb.app_type = 'PORTAL' AND pb.app_id = 'user' AND pb.subject_type IS NULL
      AND pb.org_id IS NOT DISTINCT FROM gs.org_id);

-- The legacy assignment tables are now fully represented in the matrix (IP rules stay on the policy).
DROP TABLE session_policy_user;
DROP TABLE session_policy_role;
