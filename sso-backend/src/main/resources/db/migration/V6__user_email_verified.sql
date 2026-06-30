-- First-login onboarding: track whether a user's email has been verified.
ALTER TABLE app_user ADD COLUMN email_verified boolean NOT NULL DEFAULT FALSE;
