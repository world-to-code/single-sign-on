-- Who issued a SCIM bearer token.
--
-- A SCIM client can write any attribute the tenant's profile declares, so it is an identity SOURCE in exactly
-- the sense the attribute-provenance guards mean: before a source-filled attribute is allowed to grant a role
-- or select a session policy, the person who aimed that source has to be accountable for it. A directory
-- connector records its configurator (directory_connector.configured_by); SCIM recorded nobody, so every
-- SCIM-fed attribute was permanently unattributable and could never drive either decision — silently.
--
-- ON DELETE SET NULL rather than CASCADE: deleting the administrator who issued a token must not delete the
-- token, which would be a surprise revocation of a machine integration. It becomes unattributed instead, which
-- the guards already treat as "cannot vouch" — fail-closed, and visible.
ALTER TABLE scim_token ADD COLUMN issued_by uuid REFERENCES app_user (id) ON DELETE SET NULL;

-- Every FK gets an index: this one is read per provenance check (which tokens does this org have, and who
-- issued them) and walked by the ON DELETE SET NULL when an administrator is removed.
CREATE INDEX idx_scim_token_issued_by ON scim_token (issued_by);

-- Existing tokens stay NULL on purpose. Backfilling them to "someone" would be inventing an accountability
-- record that does not exist; unattributed is the honest state, and the guards already fail closed on it.
