-- User portal: assign applications (OIDC clients / SAML SPs) to users or roles (groups).
-- An optional per-assignment auth policy lets a specific app require extra/step-up authentication.
CREATE TABLE app_assignment (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    app_type         varchar(8)  NOT NULL,   -- OIDC | SAML
    app_id           varchar(255) NOT NULL,  -- registered_client.id or saml_relying_party.id
    subject_type     varchar(8)  NOT NULL,   -- USER | ROLE
    subject_id       uuid        NOT NULL,
    required_policy_id uuid,                  -- optional per-app auth policy (extra auth)
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (app_type, app_id, subject_type, subject_id)
);
CREATE INDEX idx_app_assignment_subject ON app_assignment (subject_type, subject_id);
