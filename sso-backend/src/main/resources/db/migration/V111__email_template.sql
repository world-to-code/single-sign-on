-- Per-tenant email templates: a customer company brands the email its users receive (verification codes,
-- onboarding invitations) with its own wording and logo. A row is the acting tier's override for ONE event; a
-- tenant with no row for an event inherits the platform row, else the built-in default that lives in code. A
-- NULL org_id is an optional platform-wide default, editable only by a platform super-admin. The subject/body
-- are tenant-authored but rendered logic-less (no server-side evaluation), so they carry no execution risk.
CREATE TABLE email_template (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid REFERENCES organization (id) ON DELETE CASCADE,           -- NULL = platform-wide default
    event      text        NOT NULL,                                          -- the EmailEvent enum name
    subject    text        NOT NULL CHECK (length(subject)  <= 255),
    html_body  text        NOT NULL CHECK (length(html_body) <= 65536),
    text_body  text                 CHECK (text_body IS NULL OR length(text_body) <= 65536),
    logo_url   text                 CHECK (logo_url  IS NULL OR length(logo_url)  <= 2048),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- One row per event per tier: at most one platform-wide row per event, and one per (org, event). A plain
-- UNIQUE can't enforce the single-global case because NULL org_id compares distinct — the V85/V110 recipe.
CREATE UNIQUE INDEX uq_email_template_global ON email_template (event)         WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_email_template_org    ON email_template (org_id, event) WHERE org_id IS NOT NULL;

ALTER TABLE email_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_template FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON email_template
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
