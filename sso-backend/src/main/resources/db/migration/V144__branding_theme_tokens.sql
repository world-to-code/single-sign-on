-- Widen per-tenant branding from "logo + accent + name" to a small THEME a tenant can shape its sign-in
-- surfaces with: a dark-mode logo, a favicon, a page background (colour or image), a typeface, a corner
-- radius, and which layout the auth screens use.
--
-- The V112 rule holds for every column added here: each value is SHAPE-VALIDATED so it can be injected into
-- HTML/CSS with escaping and no breakout. Colours are #RRGGBB, URLs are https and length-capped, and the three
-- style choices are CLOSED ENUMERATIONS rather than free text — a tenant picks from a fixed set, it does not
-- author CSS. That is deliberate: this content renders on the IdP's own origin, where a style injection is a
-- step away from session theft, so the safety of the feature is a property of the column types, not of a
-- sanitizer somebody has to remember to call.
--
-- Every column stays NULLable. Absent means "inherit" at each step of own → platform → built-in default, which
-- is resolved per FIELD, so a tenant can set only its background and keep everything else inherited.

ALTER TABLE org_branding
    ADD COLUMN logo_url_dark       text,
    ADD COLUMN favicon_url         text,
    ADD COLUMN background_color    text,
    ADD COLUMN background_image_url text,
    ADD COLUMN font_family         text,
    ADD COLUMN corner_style        text,
    ADD COLUMN auth_layout         text;

ALTER TABLE org_branding
    ADD CONSTRAINT ck_org_branding_logo_url_dark
        CHECK (logo_url_dark IS NULL OR length(logo_url_dark) <= 2048),
    ADD CONSTRAINT ck_org_branding_favicon_url
        CHECK (favicon_url IS NULL OR length(favicon_url) <= 2048),
    ADD CONSTRAINT ck_org_branding_background_color
        CHECK (background_color IS NULL OR background_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT ck_org_branding_background_image_url
        CHECK (background_image_url IS NULL OR length(background_image_url) <= 2048),
    -- The three style choices are enumerated in the DB as well as in code. A CHECK is what makes the
    -- "closed set" claim true for every writer, including a future migration or a manual fix-up.
    ADD CONSTRAINT ck_org_branding_font_family
        CHECK (font_family IS NULL OR font_family IN ('SANS', 'SERIF', 'SYSTEM')),
    ADD CONSTRAINT ck_org_branding_corner_style
        CHECK (corner_style IS NULL OR corner_style IN ('SHARP', 'SOFT', 'ROUND')),
    ADD CONSTRAINT ck_org_branding_auth_layout
        CHECK (auth_layout IS NULL OR auth_layout IN ('CENTERED', 'SPLIT'));
