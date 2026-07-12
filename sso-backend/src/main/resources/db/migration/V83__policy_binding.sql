-- Unified app<->policy binding: (app x subject) -> an auth policy AND/OR a session policy in one row.
-- Generalizes app_assignment (auth, per subject), app_policy (auth, app-wide) and
-- admin_portal_settings (session, admin console) into a single model, and lets ANY session policy attach
-- to ANY app or portal. Portals are apps too (app_type PORTAL, app_id admin|user).
--
-- Resolution (per field, independently): among the bindings for this app whose subject matches the user
-- and whose field is set, pick the most SPECIFIC (USER > GROUP/ROLE > all-subjects), then highest
-- priority, then id; if none, fall back to the user's global session/login resolution -> Default.
--
-- Org-scoped exactly like app_assignment (V53): a tenant sees its own bindings plus the GLOBAL ones
-- (org_id NULL); an org binding never applies to another tenant. ON DELETE CASCADE off organization,
-- ON DELETE RESTRICT off the referenced policies (a policy in use cannot be deleted).
CREATE TABLE policy_binding (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    app_type          varchar(8)   NOT NULL,   -- OIDC | SAML | PORTAL
    app_id            varchar(255) NOT NULL,   -- registered_client.id / saml_relying_party.id / portal key (admin|user)
    subject_type      varchar(8),              -- USER | GROUP | ROLE ; NULL = every subject of the app
    subject_id        uuid,                    -- NULL exactly when subject_type is NULL
    auth_policy_id    uuid REFERENCES auth_policy (id)    ON DELETE RESTRICT,
    session_policy_id uuid REFERENCES session_policy (id) ON DELETE RESTRICT,
    priority          int          NOT NULL DEFAULT 0,   -- tie-break within the same specificity tier
    org_id            uuid REFERENCES organization (id)  ON DELETE CASCADE,  -- NULL = GLOBAL
    created_at        timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT policy_binding_has_policy
        CHECK (auth_policy_id IS NOT NULL OR session_policy_id IS NOT NULL),
    CONSTRAINT policy_binding_subject_shape
        CHECK ((subject_type IS NULL AND subject_id IS NULL)
            OR (subject_type IS NOT NULL AND subject_id IS NOT NULL)),
    CONSTRAINT policy_binding_subject_type
        CHECK (subject_type IS NULL OR subject_type IN ('USER', 'GROUP', 'ROLE')),
    CONSTRAINT policy_binding_app_type
        CHECK (app_type IN ('OIDC', 'SAML', 'PORTAL'))
);

-- One binding per (app, subject) within a tier. Split into four partial indexes because a NULL org_id
-- (global) or NULL subject (all-subjects) would defeat a single UNIQUE (nullable columns compare distinct);
-- mirrors the V53 global-vs-org recipe, extended with the all-subjects case.
CREATE UNIQUE INDEX uq_policy_binding_global_subject ON policy_binding (app_type, app_id, subject_type, subject_id)
    WHERE org_id IS NULL AND subject_type IS NOT NULL;
CREATE UNIQUE INDEX uq_policy_binding_org_subject ON policy_binding (org_id, app_type, app_id, subject_type, subject_id)
    WHERE org_id IS NOT NULL AND subject_type IS NOT NULL;
CREATE UNIQUE INDEX uq_policy_binding_global_all ON policy_binding (app_type, app_id)
    WHERE org_id IS NULL AND subject_type IS NULL;
CREATE UNIQUE INDEX uq_policy_binding_org_all ON policy_binding (org_id, app_type, app_id)
    WHERE org_id IS NOT NULL AND subject_type IS NULL;

CREATE INDEX idx_policy_binding_app ON policy_binding (app_type, app_id, org_id);
CREATE INDEX idx_policy_binding_subject ON policy_binding (subject_type, subject_id);
CREATE INDEX idx_policy_binding_auth ON policy_binding (auth_policy_id);
CREATE INDEX idx_policy_binding_session ON policy_binding (session_policy_id);

ALTER TABLE policy_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON policy_binding
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
