-- Group-scoped delegated administration: the admins (scoped ROLE_GROUP_ADMIN users) who may manage
-- the members of a group. Separate from membership (a manager need not be a member).
CREATE TABLE user_group_manager (
    group_id uuid NOT NULL REFERENCES user_group(id) ON DELETE CASCADE,
    user_id  uuid NOT NULL,
    PRIMARY KEY (group_id, user_id)
);
