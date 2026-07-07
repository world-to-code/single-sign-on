-- Collapse the customer (고객사) tier: ROLE_CUSTOMER_ADMIN no longer exists (the organization is the tenant,
-- managed by ROLE_ORG_ADMIN). Migrate every customer-admin to ROLE_ORG_ADMIN so they keep admin-console
-- access and can manage their organization, then drop the role, its grants, console assignments and permissions.
INSERT INTO app_user_role (user_id, role_id)
SELECT ur.user_id, (SELECT id FROM role WHERE name = 'ROLE_ORG_ADMIN' AND org_id IS NULL)
FROM app_user_role ur
JOIN role ca ON ca.id = ur.role_id
WHERE ca.name = 'ROLE_CUSTOMER_ADMIN' AND ca.org_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM app_user_role x
      WHERE x.user_id = ur.user_id
        AND x.role_id = (SELECT id FROM role WHERE name = 'ROLE_ORG_ADMIN' AND org_id IS NULL));

DELETE FROM app_assignment
    WHERE subject_type = 'ROLE'
      AND subject_id IN (SELECT id FROM role WHERE name = 'ROLE_CUSTOMER_ADMIN' AND org_id IS NULL);
DELETE FROM app_user_role
    WHERE role_id IN (SELECT id FROM role WHERE name = 'ROLE_CUSTOMER_ADMIN' AND org_id IS NULL);
DELETE FROM role_permission
    WHERE role_id IN (SELECT id FROM role WHERE name = 'ROLE_CUSTOMER_ADMIN' AND org_id IS NULL);
DELETE FROM role
    WHERE name = 'ROLE_CUSTOMER_ADMIN' AND org_id IS NULL;
