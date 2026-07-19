-- Inbound directory sync: a tenant PULLS profile attributes from its own directory (LDAP/LDAPS today; Google
-- Workspace and Entra ID slot in behind `kind`). SCIM already covers the push direction — an external system
-- writing into us — and federation JIT covers account creation, so this deliberately only fills attributes on
-- accounts that already exist. That keeps a mis-configured connector from mass-creating ghost accounts, and
-- leaves exactly one owner for account lifecycle.
CREATE TABLE directory_connector (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         uuid         REFERENCES organization (id) ON DELETE CASCADE,  -- NULL = platform tier
    name           varchar(64)  NOT NULL,   -- URL-safe handle, unique within the tier
    display_name   text         NOT NULL,
    kind           varchar(24)  NOT NULL,   -- LDAP (GOOGLE_WORKSPACE / ENTRA_ID later)
    enabled        boolean      NOT NULL DEFAULT true,

    -- LDAP connection. The port pair is constrained rather than free: 636 is implicit TLS, 389 is cleartext
    -- unless StartTLS upgrades it, and a bind password crossing a cleartext socket is the whole credential.
    host           text         NOT NULL,
    port           int          NOT NULL,
    use_ssl        boolean      NOT NULL DEFAULT true,    -- LDAPS (636)
    start_tls      boolean      NOT NULL DEFAULT false,   -- upgrade a 389 connection before binding
    bind_dn        text,                                  -- NULL = anonymous bind
    bind_password_encrypted text,                         -- SecretCipher ciphertext; never stored plain
    base_dn        text         NOT NULL,
    user_filter    text         NOT NULL DEFAULT '(objectClass=person)',
    -- Which directory attribute carries the stable identifier we correlate on. entryUUID for OpenLDAP,
    -- objectGUID for Active Directory. It must match what put external_id on the account in the first place.
    external_id_attribute varchar(64) NOT NULL DEFAULT 'entryUUID',

    created_at     timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT directory_connector_kind CHECK (kind IN ('LDAP', 'GOOGLE_WORKSPACE', 'ENTRA_ID')),
    -- Only the two IANA LDAP ports, mirroring the SMTP port allowlist (V110): an arbitrary port is a way to
    -- point a bind credential at something that is not a directory at all.
    CONSTRAINT directory_connector_port CHECK (port IN (389, 636)),
    -- Cleartext is refused outright: either implicit TLS, or StartTLS before the bind.
    CONSTRAINT directory_connector_transport CHECK (use_ssl OR start_tls)
);

-- The name is unique within a tier. NULLs compare distinct, so the platform tier needs its own partial index
-- (the V110/V114 recipe).
CREATE UNIQUE INDEX uq_directory_connector_org ON directory_connector (org_id, name) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX uq_directory_connector_global ON directory_connector (name) WHERE org_id IS NULL;

ALTER TABLE directory_connector ENABLE ROW LEVEL SECURITY;
ALTER TABLE directory_connector FORCE ROW LEVEL SECURITY;
-- STRICT per-tier, like identity_provider: a connector holds a bind credential for someone's directory and is
-- not inherited, so a tenant must not even read another tier's rows.
CREATE POLICY org_isolation ON directory_connector
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );


-- Which directory attribute fills which declared profile attribute. No FK to attribute_definition: the
-- definition catalog is deliberately not a constraint (V121), and a mapping that names a key nobody declared
-- must fail loudly at sync time rather than be impossible to save.
CREATE TABLE directory_attribute_mapping (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id     uuid        NOT NULL REFERENCES directory_connector (id) ON DELETE CASCADE,
    org_id           uuid        REFERENCES organization (id) ON DELETE CASCADE,
    source_attribute varchar(64) NOT NULL,   -- e.g. department, l, manager
    target_key       varchar(64) NOT NULL,   -- attribute_definition.attr_key
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- One mapping per source attribute per connector: two rows filling different targets from one source would
-- make the result order-dependent.
CREATE UNIQUE INDEX uq_directory_mapping_source ON directory_attribute_mapping (connector_id, source_attribute);
-- ...and one source per target, so a target's value has exactly one origin.
CREATE UNIQUE INDEX uq_directory_mapping_target ON directory_attribute_mapping (connector_id, target_key);
CREATE INDEX idx_directory_mapping_connector ON directory_attribute_mapping (org_id, connector_id);

ALTER TABLE directory_attribute_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE directory_attribute_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON directory_attribute_mapping
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );


-- What each run did. A sync is unattended, so unlike SCIM — which fails an HTTP request somebody is watching —
-- a failure here is invisible unless it is recorded.
CREATE TABLE directory_sync_run (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id uuid        NOT NULL REFERENCES directory_connector (id) ON DELETE CASCADE,
    org_id       uuid        REFERENCES organization (id) ON DELETE CASCADE,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    status       varchar(16) NOT NULL,   -- RUNNING | SUCCEEDED | FAILED
    -- read: entries the directory returned. matched: those we could correlate to a local account.
    -- updated: accounts whose attributes actually changed. skipped: read minus matched.
    entries_read int         NOT NULL DEFAULT 0,
    matched      int         NOT NULL DEFAULT 0,
    updated      int         NOT NULL DEFAULT 0,
    skipped      int         NOT NULL DEFAULT 0,
    error        text,

    CONSTRAINT directory_sync_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

-- The history view: newest first, per connector.
CREATE INDEX idx_directory_sync_run_connector ON directory_sync_run (org_id, connector_id, started_at DESC);

ALTER TABLE directory_sync_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE directory_sync_run FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON directory_sync_run
    USING (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
    )
    WITH CHECK (
        current_setting('app.platform', true) = 'on'
        OR org_id::text = current_setting('app.current_org', true)
        OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = '')
    );
