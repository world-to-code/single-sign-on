-- Okta-style MULTIPLE named session policies: transform the singleton (smallint id = 1) session
-- policy into multiple UUID-keyed, named, prioritised policies assignable to users/roles (mirrors
-- the auth_policy subsystem), preserving the existing singleton row as the non-editable "Default".
-- New per-policy controls: max concurrent sessions (0 = unlimited) and rotate-session-id-on-reauth.

ALTER TABLE session_policy ADD COLUMN uid uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE session_policy ADD COLUMN name varchar(100);
ALTER TABLE session_policy ADD COLUMN priority int NOT NULL DEFAULT 0;
ALTER TABLE session_policy ADD COLUMN enabled boolean NOT NULL DEFAULT true;
ALTER TABLE session_policy ADD COLUMN max_concurrent_sessions int NOT NULL DEFAULT 0;   -- 0 = unlimited
ALTER TABLE session_policy ADD COLUMN rotate_on_reauth boolean NOT NULL DEFAULT true;

-- Preserve the existing singleton row as the seeded Default fallback.
UPDATE session_policy SET name = 'Default' WHERE name IS NULL;

-- Swap the smallint singleton primary key for the new UUID identity.
ALTER TABLE session_policy DROP CONSTRAINT session_policy_pkey;
ALTER TABLE session_policy DROP COLUMN id;
ALTER TABLE session_policy RENAME COLUMN uid TO id;
ALTER TABLE session_policy ALTER COLUMN id DROP DEFAULT;
ALTER TABLE session_policy ADD PRIMARY KEY (id);

ALTER TABLE session_policy ALTER COLUMN name SET NOT NULL;
ALTER TABLE session_policy ADD CONSTRAINT uq_session_policy_name UNIQUE (name);

-- Per-policy assignments to users / roles (global when both are empty), mirroring auth_policy.
CREATE TABLE session_policy_user (
    policy_id uuid NOT NULL REFERENCES session_policy(id) ON DELETE CASCADE,
    user_id   uuid NOT NULL
);

CREATE TABLE session_policy_role (
    policy_id uuid NOT NULL REFERENCES session_policy(id) ON DELETE CASCADE,
    role_id   uuid NOT NULL
);
