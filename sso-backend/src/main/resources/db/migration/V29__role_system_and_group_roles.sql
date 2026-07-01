-- Role builder + group->role delegation.
-- (1) System roles (ROLE_ADMIN, ROLE_USER): cannot be renamed or deleted via admin; ROLE_ADMIN's
--     permissions are auto-managed (self-healed to the full catalog). Marked by DataSeeder.
ALTER TABLE role ADD COLUMN system boolean NOT NULL DEFAULT false;

-- (2) Roles delegated to a whole group: every member inherits the group's roles (and permissions).
CREATE TABLE group_role (
    group_id UUID NOT NULL REFERENCES user_group(id) ON DELETE CASCADE,
    role_id  UUID NOT NULL REFERENCES role(id)       ON DELETE CASCADE,
    PRIMARY KEY (group_id, role_id)
);
