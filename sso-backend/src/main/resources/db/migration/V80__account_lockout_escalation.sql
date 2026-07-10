-- A fixed lock window only throttles a patient attacker: they wait it out and resume. Track how many times
-- an account has locked since its last successful sign-in so each further lockout doubles the window
-- (bounded by sso.lockout.max-duration-minutes). A successful sign-in resets it to 0.
ALTER TABLE app_user ADD COLUMN lockout_count int NOT NULL DEFAULT 0;
