-- Which profile governs a user's attributes.
--
-- NULL means the organization's default-for-creation profile applies. Keeping it nullable avoids a backfill
-- that would have to guess for every existing account, and makes "the tenant changed its default" take effect
-- without rewriting every row.
--
-- ON DELETE SET NULL rather than CASCADE: deleting a profile must never delete people.
ALTER TABLE app_user ADD COLUMN profile_id uuid REFERENCES profile (id) ON DELETE SET NULL;

COMMENT ON COLUMN app_user.profile_id IS
    'The profile governing this user''s attributes; NULL falls back to the organization''s default.';

-- Serves the FK's ON DELETE SET NULL, and the "who is on this profile" listing.
CREATE INDEX idx_app_user_profile ON app_user (profile_id) WHERE profile_id IS NOT NULL;
