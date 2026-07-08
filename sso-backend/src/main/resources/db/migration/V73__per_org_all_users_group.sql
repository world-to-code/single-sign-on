-- Per-tenant "All Users" default group.
--
-- Until now every user (across ALL tenants) was a member of ONE global "All Users" group (org_id IS NULL).
-- An app assigned to that group therefore surfaced in EVERY tenant's portal — a cross-tenant leak. Going
-- forward each user joins their OWN org's "All Users" group (UserServiceImpl.addToDefaultGroup); this
-- migration provisions those per-org groups for existing tenants and moves their members off the global one.
-- The global group stays for the org-less platform super-admins. Runs as the migration owner (RLS-exempt).

-- 1. A system "All Users" group per organization that lacks one (name is unique within an org: V45).
INSERT INTO user_group (id, name, description, system, org_id, created_at)
SELECT gen_random_uuid(), 'All Users', 'Every user belongs to this group.', true, o.id, now()
FROM organization o
WHERE NOT EXISTS (
    SELECT 1 FROM user_group g WHERE g.name = 'All Users' AND g.org_id = o.id
);

-- 2. Add each tenant user to their org's "All Users" group (idempotent).
INSERT INTO user_group_member (group_id, user_id)
SELECT og.id, u.id
FROM app_user u
JOIN user_group og ON og.name = 'All Users' AND og.org_id = u.org_id
WHERE u.org_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user_group_member m WHERE m.group_id = og.id AND m.user_id = u.id
  );

-- 3. Remove tenant users from the GLOBAL "All Users" group — they now belong to their org's group only, so
--    a global assignment can no longer reach them. Global (org-less) accounts keep their global membership.
DELETE FROM user_group_member m
USING app_user u, user_group gg
WHERE m.user_id = u.id
  AND u.org_id IS NOT NULL
  AND gg.name = 'All Users' AND gg.org_id IS NULL
  AND m.group_id = gg.id;
