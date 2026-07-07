-- Collapse to org=tenant: the organization is the tenant and is addressed/resolved by a GLOBALLY-unique
-- slug (findBySlug + the {org}.base host + tenant-first login all resolve by slug alone). Restore the global
-- UNIQUE(slug) that V63 replaced with a per-customer constraint. Any pre-existing duplicate slugs (e.g. the
-- old per-customer "main" branches) are renamed — keeping the earliest row's slug — so the index can apply.
WITH ranked AS (
    SELECT id, slug, customer_id,
           row_number() OVER (PARTITION BY slug ORDER BY created_at, id) AS rn
    FROM organization
)
UPDATE organization o
SET slug = ranked.slug || '-' || left(ranked.customer_id::text, 8)
FROM ranked
WHERE o.id = ranked.id AND ranked.rn > 1;

ALTER TABLE organization DROP CONSTRAINT organization_customer_slug_key;
ALTER TABLE organization ADD CONSTRAINT organization_slug_key UNIQUE (slug);
