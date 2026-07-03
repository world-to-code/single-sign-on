-- Per-policy step-up controls for sensitive (@RequireStepUp) actions: a dedicated freshness window and
-- its own (potentially stronger) allowed factor set, separate from the general re-auth interval/factors.
ALTER TABLE session_policy
    ADD COLUMN sensitive_reauth_window_minutes integer NOT NULL DEFAULT 2,
    ADD COLUMN stepup_factors varchar(128) NOT NULL DEFAULT 'TOTP,FIDO2';
