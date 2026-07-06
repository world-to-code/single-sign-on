-- Tracks a tenant-onboarding job so the UI can poll progress (PENDING -> PROVISIONING -> INVITED/FAILED).
-- Global (not org-scoped): it is the record of a super-admin provisioning a new tenant. org_id/admin_user_id
-- are set once provisioned; ON DELETE SET NULL keeps the onboarding history if the org/user is later removed.
CREATE TABLE onboarding (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    slug          varchar(63)  NOT NULL,
    status        varchar(16)  NOT NULL,
    org_id        uuid REFERENCES organization (id) ON DELETE SET NULL,
    admin_user_id uuid REFERENCES app_user (id)     ON DELETE SET NULL,
    error         varchar(500)
);
