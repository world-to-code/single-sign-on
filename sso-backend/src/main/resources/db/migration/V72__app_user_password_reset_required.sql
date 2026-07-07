-- Force a password reset on first login for admin-created users given a TEMPORARY password. Set when an
-- admin creates a user with a password; cleared the moment the user sets their own password. Login
-- completion refuses to finalize (no MFA_COMPLETE) while this is true, routing the user to a reset step.
ALTER TABLE app_user ADD COLUMN password_reset_required boolean NOT NULL DEFAULT false;
