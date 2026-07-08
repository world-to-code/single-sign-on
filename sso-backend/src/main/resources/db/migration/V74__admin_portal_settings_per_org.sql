-- Per-tenant admin-portal settings. The admin-console elevation-path policy (re-auth freshness, admin
-- session lifetimes, IP allowlist, elevation-token TTL) becomes per-organization: the AdminElevationFilter
-- resolves the acting tenant and reads its row, falling back to the GLOBAL default (org_id NULL) — the
-- original single row, retained — until a tenant saves its own (copy-on-write). No RLS (like app_user /
-- audit_event): this is read on pre-context/elevation paths, so isolation is enforced in the app layer by
-- resolving the acting org. The elevation-token TTL no longer syncs to the shared admin-console OAuth client
-- (one client serves every tenant); the filter enforces the per-tenant TTL by the token's age instead.

-- 1. Retire the id=1 singleton pin so multiple rows (one per org + one global) can coexist.
ALTER TABLE admin_portal_settings DROP CONSTRAINT admin_portal_settings_singleton;

-- 2. Tenant discriminator (NULL = the global default every tenant inherits until it customizes).
ALTER TABLE admin_portal_settings ADD COLUMN org_id uuid;

-- 3. Swap the pinned int id for a surrogate UUID key (backfills the existing global row).
ALTER TABLE admin_portal_settings ADD COLUMN new_id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE admin_portal_settings DROP CONSTRAINT admin_portal_settings_pkey;
ALTER TABLE admin_portal_settings DROP COLUMN id;
ALTER TABLE admin_portal_settings RENAME COLUMN new_id TO id;
ALTER TABLE admin_portal_settings ADD PRIMARY KEY (id);
ALTER TABLE admin_portal_settings ALTER COLUMN id DROP DEFAULT; -- Hibernate assigns the UUID

-- 4. At most one row per tenant, and at most one global default.
CREATE UNIQUE INDEX ux_admin_portal_settings_org ON admin_portal_settings (org_id) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX ux_admin_portal_settings_global ON admin_portal_settings ((org_id IS NULL)) WHERE org_id IS NULL;
