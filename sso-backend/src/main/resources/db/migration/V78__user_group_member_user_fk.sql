-- user_group_member referenced app_user without a foreign key, so deleting a user left their membership rows
-- behind. The group's member COUNT (join rows) then exceeded its member LIST (joined to app_user), and the
-- orphaned ids kept flowing into group-based role delegation and app assignments.
--
-- 1. Reap the rows already orphaned.
DELETE FROM user_group_member m
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = m.user_id);

-- 2. Let the database guarantee it from now on: the application deletes the rows explicitly (so the code
--    documents what disappears with a user), and this cascade covers every other deletion path.
ALTER TABLE user_group_member
    ADD CONSTRAINT user_group_member_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE;

-- 3. The PK is (group_id, user_id), so a membership lookup BY USER (delete-by-user, "which groups is this
--    user in") had no index to use.
CREATE INDEX idx_user_group_member_user ON user_group_member (user_id);
