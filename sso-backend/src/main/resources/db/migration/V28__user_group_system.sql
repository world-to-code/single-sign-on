-- Platform-managed groups (e.g. the "All Users" default group): auto-managed membership, and the
-- group itself cannot be renamed or deleted via admin. Seeded/backfilled by AllUsersGroupSeeder.
ALTER TABLE user_group ADD COLUMN system boolean NOT NULL DEFAULT false;
