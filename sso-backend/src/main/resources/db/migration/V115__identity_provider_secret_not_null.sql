-- The token exchange (increment 1b) decrypts client_secret_encrypted, so a NULL would fail at login. The
-- service already guarantees it on write (a new provider must carry a secret; a blank edit keeps the stored
-- one), so enforce the invariant at the schema now that the decrypt path exists. The table is new — no
-- pre-existing NULL rows to backfill.
ALTER TABLE identity_provider ALTER COLUMN client_secret_encrypted SET NOT NULL;
