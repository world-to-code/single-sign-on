-- Okta-style session management: a singleton session policy + an IP access rule list.

CREATE TABLE session_policy (
    id                       smallint     PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- singleton
    absolute_timeout_minutes integer      NOT NULL DEFAULT 480,  -- max session lifetime (full re-auth)
    idle_timeout_minutes     integer      NOT NULL DEFAULT 30,   -- inactivity timeout
    reauth_interval_minutes  integer      NOT NULL DEFAULT 5,    -- step-up re-auth window for sensitive ops
    reauth_factors           varchar(128) NOT NULL DEFAULT 'TOTP,FIDO2',
    bind_client              boolean      NOT NULL DEFAULT TRUE, -- bind session to its User-Agent
    cookie_same_site         varchar(10)  NOT NULL DEFAULT 'Lax',-- Lax | Strict | None
    cookie_secure            boolean      NOT NULL DEFAULT FALSE -- HTTPS-only session cookie
);
INSERT INTO session_policy (id) VALUES (1);

CREATE TABLE ip_rule (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    cidr        varchar(64)  NOT NULL,            -- e.g. 203.0.113.0/24 or 2001:db8::/32
    action      varchar(8)   NOT NULL,            -- ALLOW | BLOCK
    description varchar(255),
    enabled     boolean      NOT NULL DEFAULT TRUE,
    priority    integer      NOT NULL DEFAULT 100, -- lower evaluated first
    created_at  timestamptz  NOT NULL DEFAULT now()
);
