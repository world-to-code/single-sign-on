-- Multi-tenancy foundation (Auth0-Organizations model).
--
-- `organization` is the tenant registry: it is GLOBAL, not org-scoped (no org_id / RLS) — access is
-- guarded by organization:* permissions only. `organization_membership` links a GLOBAL user identity to
-- many organizations (a user may belong to several); it references app_user by id. This membership table
-- IS org-scoped and receives its org_id/RLS discriminator in the isolation phase (V42).
--
-- We seed a `default` organization with a fixed id and backfill every existing user into it, so the
-- pre-tenant single-pool data becomes one tenant. (The nil UUID 000..000 is reserved as the platform
-- sentinel in the isolation phase, so the default org uses 000..001.)

CREATE TABLE organization (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       varchar(63)  NOT NULL UNIQUE,
    name       varchar(255) NOT NULL,
    status     varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE organization_membership (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES app_user (id)     ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (org_id, user_id)
);
CREATE INDEX idx_org_membership_org  ON organization_membership (org_id);
CREATE INDEX idx_org_membership_user ON organization_membership (user_id);

INSERT INTO organization (id, slug, name)
    VALUES ('00000000-0000-0000-0000-000000000001', 'default', 'Default');

INSERT INTO organization_membership (org_id, user_id)
    SELECT '00000000-0000-0000-0000-000000000001', id FROM app_user;
