-- Phone number for the SMS one-time-code factor. app_user carries global identity (no RLS); the number is
-- per-user, unproven until the owner redeems a texted code (phone_verified), mirroring email/email_verified.
ALTER TABLE app_user
    ADD COLUMN phone_number  varchar(32),
    ADD COLUMN phone_verified boolean NOT NULL DEFAULT false;

-- Loose E.164 shape (leading +, up to 15 digits) — a format guard, not validation of a live number.
ALTER TABLE app_user
    ADD CONSTRAINT app_user_phone_e164 CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9][0-9]{1,14}$');
