-- Per-tenant wording for the sign-in screens. A tenant can already be recognised by its logo and colours;
-- this lets it say something in its own voice on the screen where people arrive — "Sign in to the Acme
-- partner portal" rather than the product's generic heading — and point at its own help desk.
--
-- Every column is TEXT that is rendered as TEXT. Unlike the email templates there is no variable
-- substitution here and therefore no template engine: a screen heading has nothing to interpolate, so the
-- narrower thing to build is the one that cannot interpolate. Nothing on this table is ever parsed as
-- markup, which is the whole of its safety story.
--
-- One row per (tier, screen); a NULL org_id is the platform-wide default. Absent means "inherit", resolved
-- per FIELD like org_branding, so a tenant can set a headline and keep the inherited footer.

CREATE TABLE auth_screen_copy (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid REFERENCES organization (id) ON DELETE CASCADE,   -- NULL = platform-wide default
    -- Code-bound, not data: each value names a screen the SPA implements, so the set closes in the enum and
    -- the CHECK keeps any other writer to it.
    screen     text        NOT NULL CHECK (screen IN ('LOGIN', 'MFA', 'STEPUP', 'CONSENT', 'RESET')),
    headline   text                 CHECK (headline IS NULL OR length(headline) <= 120),
    subtext    text                 CHECK (subtext  IS NULL OR length(subtext)  <= 300),
    footer     text                 CHECK (footer   IS NULL OR length(footer)   <= 200),
    help_url   text                 CHECK (help_url IS NULL OR length(help_url) <= 2048),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- At most one row per screen per tier (the V85/V110/V112 tier-aware partial-index recipe: a plain UNIQUE
-- over a nullable org_id would let a tenant hold several global rows, because NULLs do not collide).
CREATE UNIQUE INDEX uq_auth_screen_copy_global ON auth_screen_copy (screen)         WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_auth_screen_copy_org    ON auth_screen_copy (org_id, screen) WHERE org_id IS NOT NULL;

-- The FK's own index. uq_auth_screen_copy_org leads with org_id so it serves the cascade; stated rather than
-- left to chance, because a composite index does NOT serve a predicate on a non-leading column.

ALTER TABLE auth_screen_copy ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_screen_copy FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON auth_screen_copy
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
