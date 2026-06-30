-- Temporary account lockout after repeated failed logins (brute-force defense).
ALTER TABLE app_user ADD COLUMN failed_login_attempts int NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until timestamptz;
