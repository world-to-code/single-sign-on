-- The EMAIL one-time-code factor now refuses to send to an address whose ownership was never proven
-- (email_verified = false), because a code delivered to an unproven mailbox authenticates whoever holds it.
--
-- Existing addresses were set at account creation, before the flag gated anything, and every one of them is
-- already in use as a login identifier. Failing them closed would silently remove the EMAIL factor from every
-- pre-existing user. Grandfather them instead: the guarantee starts NOW — any CHANGE to an address from here
-- on clears the flag (AppUser.updateProfile) and must be re-proven before it can receive codes again.
--
-- app_user has no RLS, so this reaches every tenant's rows.
UPDATE app_user SET email_verified = true WHERE email_verified = false;
