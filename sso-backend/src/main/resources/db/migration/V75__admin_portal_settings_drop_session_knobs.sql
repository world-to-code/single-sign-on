-- The admin-console session lifetimes (idle/absolute) and the step-up freshness window now come from the
-- SESSION POLICY resolved for the admin user (assign a session policy to their role/user and it governs the
-- admin-console session too), not from separate admin_portal_settings knobs. admin_portal_settings keeps
-- only what is genuinely admin-console-specific: the elevation-token TTL (the OAuth proof) and the IP
-- allowlist. AdminElevationFilter reads the freshness from the policy's sensitive-reauth window; the regular
-- SessionIntegrityFilter already enforces the policy's idle/absolute for every request, admin included.
ALTER TABLE admin_portal_settings
    DROP COLUMN reauth_interval_minutes,
    DROP COLUMN session_idle_timeout_minutes,
    DROP COLUMN session_absolute_lifetime_minutes;
