-- Per-organization user identity: username/email become unique WITHIN an organization (the tenant), not
-- globally, so the same login may be a different user in different orgs (e.g. signing up with an email
-- already used in another company now succeeds). The platform super-admin (org_id IS NULL) keeps GLOBAL
-- uniqueness. Split each global UNIQUE(username)/UNIQUE(email) into two partial unique indexes. Safe now
-- that every login resolves the user within their organization (V67 + org-scoped resolution).
ALTER TABLE app_user DROP CONSTRAINT app_user_username_key;
ALTER TABLE app_user DROP CONSTRAINT app_user_email_key;

-- Within an organization: username/email unique among that org's users.
CREATE UNIQUE INDEX uq_app_user_org_username ON app_user (org_id, username) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX uq_app_user_org_email    ON app_user (org_id, email)    WHERE org_id IS NOT NULL;

-- Global accounts (platform super-admin, org_id IS NULL): username/email unique across all such rows.
CREATE UNIQUE INDEX uq_app_user_global_username ON app_user (username) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_app_user_global_email    ON app_user (email)    WHERE org_id IS NULL;
