-- Move "allow MFA/passkey enrollment during login" from the global session policy to a PER-POLICY
-- flag on auth_policy (Okta-style enrollment policy): the winning login policy for a user decides
-- whether they may set up a missing required factor at login.
ALTER TABLE auth_policy ADD COLUMN allow_enrollment_at_login boolean NOT NULL DEFAULT TRUE;

ALTER TABLE session_policy DROP COLUMN allow_mfa_enrollment_at_login;
