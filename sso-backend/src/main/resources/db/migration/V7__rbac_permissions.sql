-- PBAC: fine-grained permissions attached to roles (RBAC roles -> permissions).
CREATE TABLE permission (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(64) NOT NULL UNIQUE   -- e.g. user:read, user:write, key:rotate
);

CREATE TABLE role_permission (
    role_id       UUID NOT NULL REFERENCES role(id)       ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
