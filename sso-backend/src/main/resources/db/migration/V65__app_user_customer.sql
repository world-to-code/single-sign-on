-- Move user identity toward PER-CUSTOMER isolation: the same email may become a different user in different
-- customers (고객사). This first step is ADDITIVE and INERT — it adds the owning customer to app_user and
-- backfills existing users, but leaves the global UNIQUE(email)/UNIQUE(username) constraints and all user
-- resolution UNCHANGED for now. A later phase swaps the uniqueness to per-customer once login resolves users
-- within their customer, so the two never disagree. NULL customer_id = the global platform super-admin.
ALTER TABLE app_user ADD COLUMN customer_id uuid REFERENCES customer (id);
CREATE INDEX idx_app_user_customer ON app_user (customer_id);

-- Backfill each existing user's owning customer, derived from their memberships: a customer admin's own
-- customer, else the customer that owns an org they belong to, else the default customer (…0002). The platform
-- super-admin (holds the global ROLE_ADMIN, org_id IS NULL) stays NULL (global). New users keep NULL until a
-- later phase stamps them at creation. On a fresh database app_user is empty here, so this affects nothing.
WITH platform_admin AS (
    SELECT ur.user_id
    FROM app_user_role ur
    JOIN role r ON r.id = ur.role_id
    WHERE r.name = 'ROLE_ADMIN' AND r.org_id IS NULL
)
UPDATE app_user u
SET customer_id = COALESCE(
        (SELECT cm.customer_id FROM customer_membership cm WHERE cm.user_id = u.id LIMIT 1),
        (SELECT o.customer_id FROM organization_membership m
                JOIN organization o ON o.id = m.org_id WHERE m.user_id = u.id LIMIT 1),
        '00000000-0000-0000-0000-000000000002')
WHERE u.id NOT IN (SELECT user_id FROM platform_admin);
