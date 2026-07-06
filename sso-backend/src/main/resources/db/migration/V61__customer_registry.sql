-- Customer (고객사) — the NEW top tenancy tier ABOVE organization. An organization is now a "branch" that
-- belongs to exactly one customer (고객사 > 지부/organization > 팀·부서/resource). Like `organization`, the
-- customer registry is GLOBAL (not org-scoped, no RLS) — access is guarded by `customer:*` permissions.
CREATE TABLE customer (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    slug        varchar(63) NOT NULL UNIQUE,
    name        varchar(255) NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'ACTIVE'
);

-- Fixed-id default customer (…0002; …0001 is the default org, …0000 the platform sentinel). Every existing
-- organization is backfilled under it so the "every org has a customer" invariant holds from this migration on.
INSERT INTO customer (id, slug, name)
    VALUES ('00000000-0000-0000-0000-000000000002', 'default', 'Default');

-- organization gains its parent link. Plain FK (both tables are global/non-RLS) — no RLS policy needed.
ALTER TABLE organization ADD COLUMN customer_id uuid REFERENCES customer (id);
UPDATE organization SET customer_id = '00000000-0000-0000-0000-000000000002';
ALTER TABLE organization ALTER COLUMN customer_id SET NOT NULL;
CREATE INDEX idx_organization_customer ON organization (customer_id);
