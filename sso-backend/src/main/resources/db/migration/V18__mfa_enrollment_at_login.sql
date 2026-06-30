-- Keycloak-style toggle: whether an un-enrolled user is prompted to set up a required MFA factor
-- (e.g. TOTP) during login. When false, login only verifies factors the user already has.
ALTER TABLE session_policy ADD COLUMN allow_mfa_enrollment_at_login boolean NOT NULL DEFAULT TRUE;
