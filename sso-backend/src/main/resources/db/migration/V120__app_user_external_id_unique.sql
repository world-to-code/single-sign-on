-- external_id is the identifier an external directory (SCIM/LDAP) assigned to an account, and federated
-- sign-in now resolves by it. Nothing enforced that it identifies ONE account, so the resolver had to return
-- "empty" whenever several rows matched — an application-side rule for an invariant the schema should hold,
-- and with no index the lookup sequential-scanned the largest table on every first federated login.
--
-- Partial (external_id IS NOT NULL) because most accounts are locally managed, and per-org because an
-- organization owns its own app_user rows (V68) — two tenants provisioned from the same directory legitimately
-- carry the same identifier.
CREATE UNIQUE INDEX uq_app_user_org_external_id
    ON app_user (org_id, external_id)
    WHERE external_id IS NOT NULL AND org_id IS NOT NULL;

-- The platform tier owns org-less accounts; keep the same guarantee there.
CREATE UNIQUE INDEX uq_app_user_global_external_id
    ON app_user (external_id)
    WHERE external_id IS NOT NULL AND org_id IS NULL;
