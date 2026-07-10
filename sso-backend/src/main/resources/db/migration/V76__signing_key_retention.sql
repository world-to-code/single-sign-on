-- Per-tenant JWKS retention: how many rotated-away (inactive) signing keys a tier keeps PUBLISHED in its
-- JWKS so tokens signed before a rotation stay verifiable until they expire. One row per organization plus
-- an optional GLOBAL default (org_id NULL) every tenant inherits until it saves its own (copy-on-write,
-- like admin_portal_settings); with no rows at all the application.yml default applies. NO RLS: the JWK
-- source reads this on browser-less signing paths too (OIDC back-channel logout), where an RLS'd read
-- breaks logout propagation — scoping happens in the query by the acting tier.
CREATE TABLE signing_key_retention (
    id                     uuid PRIMARY KEY,
    org_id                 uuid REFERENCES organization (id) ON DELETE CASCADE,
    retained_inactive_keys int NOT NULL,
    updated_at             timestamptz NOT NULL
);

-- At most one row per tenant, and at most one global default (also the org_id lookup index).
CREATE UNIQUE INDEX ux_signing_key_retention_org ON signing_key_retention (org_id) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX ux_signing_key_retention_global ON signing_key_retention ((org_id IS NULL)) WHERE org_id IS NULL;
