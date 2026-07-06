-- Branch (organization) slugs are unique PER CUSTOMER, not globally: two customers (고객사) may each have a
-- branch named "seoul" ({branch}.{customer} keeps them apart). The single-label {org}.base host + tenant-first
-- login resolve within the DEFAULT customer's namespace (see OrganizationServiceImpl.findBySlug), so that
-- legacy path stays unambiguous.
ALTER TABLE organization DROP CONSTRAINT organization_slug_key;
ALTER TABLE organization ADD CONSTRAINT organization_customer_slug_key UNIQUE (customer_id, slug);
