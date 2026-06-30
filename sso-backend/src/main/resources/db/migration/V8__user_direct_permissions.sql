-- Per-user permissions (Okta/AWS-style direct grants), in addition to role-derived ones.
CREATE TABLE app_user_permission (
    user_id       UUID NOT NULL REFERENCES app_user(id)   ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, permission_id)
);
