-- Admin-portal-specific security settings, editable at runtime from the admin console.
-- Single-row table (id is pinned to 1) so the whole IdP shares one admin-portal policy.
--   reauth_interval_minutes           : how recent the step-up (stepup_time) must be on /api/admin/**
--   elevation_token_ttl_minutes       : admin-console access-token lifetime (the elevation proof)
--   session_idle_timeout_minutes      : admin session expires after this long with no admin activity
--   session_absolute_lifetime_minutes : admin session expires this long after it first elevated
-- Defaults mirror the previous application.yml values (freshness 10, token TTL 5, global session 30/480).
CREATE TABLE admin_portal_settings (
    id                                INT PRIMARY KEY,
    reauth_interval_minutes           INT NOT NULL,
    elevation_token_ttl_minutes       INT NOT NULL,
    session_idle_timeout_minutes      INT NOT NULL,
    session_absolute_lifetime_minutes INT NOT NULL,
    updated_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT admin_portal_settings_singleton CHECK (id = 1)
);

INSERT INTO admin_portal_settings
    (id, reauth_interval_minutes, elevation_token_ttl_minutes,
     session_idle_timeout_minutes, session_absolute_lifetime_minutes)
VALUES (1, 10, 5, 30, 480);
