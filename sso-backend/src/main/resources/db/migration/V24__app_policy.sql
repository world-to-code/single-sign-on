-- Per-application sign-on policy: an OIDC client / SAML SP can require a specific auth policy
-- (extra/step-up factors) for EVERY user accessing it, independent of per-subject assignments.
-- One policy per app (app_type + app_id).
CREATE TABLE app_policy (
    id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    app_type           varchar(8)   NOT NULL,   -- OIDC | SAML
    app_id             varchar(255) NOT NULL,   -- registered_client.id or saml_relying_party.id
    required_policy_id uuid         NOT NULL,    -- the auth_policy required to access this app
    created_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (app_type, app_id)
);
