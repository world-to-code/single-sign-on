-- Pending self-service signup awaiting EMAIL VERIFICATION. Public signup records the requested workspace +
-- admin here and emails a one-time link; the org + admin are provisioned only when the applicant redeems it
-- (proving control of the email), so an anonymous request can never squat a third party's email or org slug.
-- Intentionally references NOTHING (no org/user exist yet). GLOBAL (not org-scoped) — resolved by token hash
-- on an unauthenticated request. Only the SHA-256 hash of the token is stored. Single-use (used_at) + timeboxed.
CREATE TABLE signup_request (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    slug              varchar(255) NOT NULL,
    name              varchar(255) NOT NULL,
    admin_email       varchar(255) NOT NULL,
    admin_name        varchar(255) NOT NULL,
    company_size      varchar(32),
    company_country   varchar(64),
    company_industry  varchar(64),
    company_phone     varchar(32),
    token_hash        varchar(128) NOT NULL UNIQUE,
    expires_at        timestamptz NOT NULL,
    used_at           timestamptz
);
