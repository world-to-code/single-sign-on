-- Per-tenant auth-UI branding: a customer company's login / MFA / consent screens show its own logo, accent
-- color and product name. A row is the acting tier's override; a tenant with no row inherits the platform row,
-- else the built-in default in code. A NULL org_id is the optional platform-wide default. Branding is PUBLIC
-- (shown to every visitor of the tenant's subdomain) — nothing here is a secret; the columns are validated
-- for shape (https logo, #RRGGBB accent) so they can be injected into HTML/CSS safely downstream.
CREATE TABLE org_branding (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid REFERENCES organization (id) ON DELETE CASCADE,          -- NULL = platform-wide default
    logo_url     text                 CHECK (logo_url     IS NULL OR length(logo_url)     <= 2048),
    accent_color text                 CHECK (accent_color IS NULL OR accent_color ~ '^#[0-9a-fA-F]{6}$'),
    product_name text                 CHECK (product_name IS NULL OR length(product_name) <= 64),
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- At most one platform-wide row and one per tenant (the V85/V110 tier-aware partial-index recipe).
CREATE UNIQUE INDEX uq_org_branding_global ON org_branding ((true))  WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_org_branding_org    ON org_branding (org_id)  WHERE org_id IS NOT NULL;

ALTER TABLE org_branding ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_branding FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON org_branding
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id IS NULL
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );
