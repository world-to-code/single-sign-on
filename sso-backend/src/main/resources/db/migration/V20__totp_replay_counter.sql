-- TOTP replay protection (RFC 6238 §5.2): remember the last accepted time-step per factor so a
-- captured code cannot be reused within its ±window validity.
ALTER TABLE mfa_factor ADD COLUMN last_used_step bigint;
