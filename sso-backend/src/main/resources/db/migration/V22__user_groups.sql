-- Organizational "Groups": directory containers that bundle USERS (org/department membership),
-- SEPARATE from RBAC roles and the policy/app assignment subsystems. Sync-ready: each group can
-- carry an external_id so a future LDAP/SCIM integration can populate department info.

CREATE TABLE user_group (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL UNIQUE,
    description varchar(255),
    external_id varchar(255),                 -- source id for future LDAP/SCIM sync (nullable)
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_group_external_id ON user_group(external_id) WHERE external_id IS NOT NULL;

CREATE TABLE user_group_member (
    group_id uuid NOT NULL REFERENCES user_group(id) ON DELETE CASCADE,
    user_id  uuid NOT NULL,
    PRIMARY KEY (group_id, user_id)
);
