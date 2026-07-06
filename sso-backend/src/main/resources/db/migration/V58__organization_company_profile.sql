-- Okta/Ping-style company profile collected at tenant onboarding. All optional, embedded in the org row
-- (the organization registry is global — not org-scoped). Backfilled as NULL for existing tenants.
ALTER TABLE organization
    ADD COLUMN company_size     varchar(32),
    ADD COLUMN company_country  varchar(64),
    ADD COLUMN company_industry varchar(64),
    ADD COLUMN company_phone    varchar(32);
