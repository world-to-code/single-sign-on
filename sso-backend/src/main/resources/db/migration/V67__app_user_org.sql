-- Collapse the customer (고객사) tier: the ORGANIZATION becomes the identity boundary. This first step is
-- ADDITIVE and INERT — it adds the owning organization to app_user and backfills existing users, but leaves
-- resolution and the (still GLOBAL) username/email uniqueness UNCHANGED. A later phase resolves users within
-- their organization and swaps uniqueness to per-organization. NULL org_id = the global platform super-admin.
ALTER TABLE app_user ADD COLUMN org_id uuid REFERENCES organization (id);
CREATE INDEX idx_app_user_org ON app_user (org_id);

-- Each existing user's owning org: the organization they are a member of, else the (single) org of the
-- customer that currently owns them. The platform super-admin (customer_id IS NULL) stays NULL (global).
UPDATE app_user u
SET org_id = COALESCE(
        (SELECT m.org_id FROM organization_membership m WHERE m.user_id = u.id LIMIT 1),
        (SELECT o.id FROM organization o WHERE o.customer_id = u.customer_id LIMIT 1))
WHERE u.customer_id IS NOT NULL;
