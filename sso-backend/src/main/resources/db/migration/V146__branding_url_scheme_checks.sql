-- Make the claim V144 already makes actually true.
--
-- V144's header says the safety of tenant branding "is a property of the column types, not of a sanitizer
-- somebody has to remember to call". That held for the colours (#RRGGBB regex) and the three style choices
-- (IN (...) lists), and NOT for the five columns that reach a URL sink: logo_url, logo_url_dark, favicon_url,
-- background_image_url and help_url were length-capped only. The https guarantee lived solely in the service
-- layer, and every reader downstream — resolve() -> /api/auth/branding -> the sign-in screen -> an <a href> —
-- trusts the row without re-checking. A row written by a future migration, a manual fix-up, or any writer
-- that forgets the service would have carried a `javascript:` URL to every pre-authentication visitor of that
-- tenant's subdomain.
--
-- These values render on the IdP's OWN origin, on the page where every user of every downstream application
-- signs in, so the scheme is worth stating in the one place no writer can bypass.
--
-- The predicates are NULLable-tolerant (absent means "inherit") and case-sensitive on purpose: a scheme is
-- lowercase by RFC 3986 convention, and all three layers now agree on that. They did not at first — the
-- service compared case-insensitively and stored the original casing, so an uppercase scheme passed it and
-- was refused HERE at commit-flush, turning a validation into a 500. An
-- existing row that violates this would fail the migration rather than be silently rewritten — deliberate,
-- since a non-https URL already in the table is a finding, not a formatting problem.

ALTER TABLE org_branding
    ADD CONSTRAINT ck_org_branding_logo_url_https
        CHECK (logo_url IS NULL OR logo_url LIKE 'https://%'),
    ADD CONSTRAINT ck_org_branding_logo_url_dark_https
        CHECK (logo_url_dark IS NULL OR logo_url_dark LIKE 'https://%'),
    ADD CONSTRAINT ck_org_branding_favicon_url_https
        CHECK (favicon_url IS NULL OR favicon_url LIKE 'https://%'),
    ADD CONSTRAINT ck_org_branding_background_image_url_https
        CHECK (background_image_url IS NULL OR background_image_url LIKE 'https://%');

ALTER TABLE auth_screen_copy
    ADD CONSTRAINT ck_auth_screen_copy_help_url_https
        CHECK (help_url IS NULL OR help_url LIKE 'https://%');
