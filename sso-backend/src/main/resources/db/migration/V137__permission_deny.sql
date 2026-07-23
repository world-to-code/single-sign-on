-- Negative permissions (DENY): subtract a permission (or a <resource>:* wildcard) from what a subject would
-- otherwise hold, so an admin can grant a bundle and carve ONE thing out instead of minting a new role per
-- combination. Three subject tiers mirror the specificity ladder USER > (GROUP = ROLE) > ORG; a platform-tier
-- deny (org_id NULL on org_permission_deny) is an ABSOLUTE veto no tenant grant overrides.
--
-- A negative authority is too dangerous to isolate only by the indirect visibility of the role/group it hangs
-- off (the grant join tables have no org_id of their own), so each deny table carries its OWN org_id and FORCED
-- RLS. The RLS is SPLIT by command: a tenant may SEE a platform deny (org_id NULL) for provenance, but the
-- INSERT/UPDATE/DELETE policies omit the org_id-IS-NULL branch, so a tenant can never author over or DELETE a
-- platform veto. Copy-pasting the usual single USING clause (which opens org_id IS NULL to every tenant, and
-- USING governs DELETE) would have let a tenant delete the platform veto — this is the plan's P0 #2.
--
-- created_by + writer_apex_role_id record WHO authored the deny and their apex role at the time, for the lift
-- guard (removing a deny is a grant act; a peer admin must not lift a peer's deny) — read by the write path.

-- Composite unique so a USER deny's (user_id, org_id) can be FK-checked against the user's OWN org: a tenant
-- can never author a deny that names a user in another org (id is already the PK, so this is trivially unique).
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_id_org UNIQUE (id, org_id);

-- USER-level deny (most specific).
CREATE TABLE app_user_permission_deny (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid        NOT NULL,
    org_id              uuid,                                       -- matches the target user's tier (NULL = platform)
    pattern             varchar(64) NOT NULL,                       -- a permission name or a <resource>:* wildcard
    created_by          uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    writer_apex_role_id uuid        REFERENCES role (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_user_deny_user FOREIGN KEY (user_id, org_id) REFERENCES app_user (id, org_id) ON DELETE CASCADE,
    CONSTRAINT uq_user_deny UNIQUE (user_id, pattern)
);
CREATE INDEX idx_user_deny_user ON app_user_permission_deny (user_id);
CREATE INDEX idx_user_deny_org  ON app_user_permission_deny (org_id);
-- FK indexes so ON DELETE SET NULL (author/apex removal) does not seq-scan the deny table.
CREATE INDEX idx_user_deny_created_by ON app_user_permission_deny (created_by);
CREATE INDEX idx_user_deny_apex       ON app_user_permission_deny (writer_apex_role_id);

-- GROUP / ROLE deny, unified as a "principal" and referenced BY ID (a role NAME has global/org collisions, V43,
-- which have already been a real escalation path — deny keys on id like the grant ceiling does).
CREATE TABLE principal_permission_deny (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type        varchar(8)  NOT NULL,                       -- GROUP | ROLE
    subject_id          uuid        NOT NULL,
    org_id              uuid        REFERENCES organization (id) ON DELETE CASCADE,
    pattern             varchar(64) NOT NULL,
    created_by          uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    writer_apex_role_id uuid        REFERENCES role (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_principal_deny UNIQUE (subject_type, subject_id, pattern)
);
CREATE INDEX idx_principal_deny_subject ON principal_permission_deny (subject_type, subject_id);
CREATE INDEX idx_principal_deny_org     ON principal_permission_deny (org_id);
CREATE INDEX idx_principal_deny_created_by ON principal_permission_deny (created_by);
CREATE INDEX idx_principal_deny_apex       ON principal_permission_deny (writer_apex_role_id);

-- ORG-level deny (least specific). org_id NULL = the PLATFORM veto: absolute, tenant-unwritable.
CREATE TABLE org_permission_deny (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid        REFERENCES organization (id) ON DELETE CASCADE,   -- NULL = platform veto
    pattern             varchar(64) NOT NULL,
    created_by          uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    writer_apex_role_id uuid        REFERENCES role (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);
-- One deny per (tier, pattern): a global veto and a tenant's own deny of the same permission may coexist.
CREATE UNIQUE INDEX uq_org_deny_org      ON org_permission_deny (org_id, pattern) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX uq_org_deny_platform ON org_permission_deny (pattern)         WHERE org_id IS NULL;
CREATE INDEX idx_org_deny_org ON org_permission_deny (org_id);
CREATE INDEX idx_org_deny_created_by ON org_permission_deny (created_by);
CREATE INDEX idx_org_deny_apex       ON org_permission_deny (writer_apex_role_id);

-- RLS: SELECT sees platform (org_id NULL) + own; INSERT/UPDATE/DELETE touch only own (no org_id-IS-NULL branch),
-- so a platform veto is readable by a tenant but writable/deletable ONLY from the platform context.
ALTER TABLE app_user_permission_deny ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user_permission_deny FORCE  ROW LEVEL SECURITY;
CREATE POLICY deny_select ON app_user_permission_deny FOR SELECT
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_insert ON app_user_permission_deny FOR INSERT
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_update ON app_user_permission_deny FOR UPDATE
    USING (current_setting('app.platform', true) = 'on'
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_delete ON app_user_permission_deny FOR DELETE
    USING (current_setting('app.platform', true) = 'on'
           OR org_id::text = current_setting('app.current_org', true));

ALTER TABLE principal_permission_deny ENABLE ROW LEVEL SECURITY;
ALTER TABLE principal_permission_deny FORCE  ROW LEVEL SECURITY;
CREATE POLICY deny_select ON principal_permission_deny FOR SELECT
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_insert ON principal_permission_deny FOR INSERT
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_update ON principal_permission_deny FOR UPDATE
    USING (current_setting('app.platform', true) = 'on'
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_delete ON principal_permission_deny FOR DELETE
    USING (current_setting('app.platform', true) = 'on'
           OR org_id::text = current_setting('app.current_org', true));

ALTER TABLE org_permission_deny ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_permission_deny FORCE  ROW LEVEL SECURITY;
CREATE POLICY deny_select ON org_permission_deny FOR SELECT
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_insert ON org_permission_deny FOR INSERT
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_update ON org_permission_deny FOR UPDATE
    USING (current_setting('app.platform', true) = 'on'
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true));
CREATE POLICY deny_delete ON org_permission_deny FOR DELETE
    USING (current_setting('app.platform', true) = 'on'
           OR org_id::text = current_setting('app.current_org', true));
