-- Resource DAG for subtree-scoped delegated administration (Phase 0: graph model only — nothing is
-- wired to the admin API yet). A Resource is an organizational unit bundling child resources plus
-- polymorphic leaf members (groups / applications / users); resource admins are scoped to the subtree
-- reachable from the resources they administer.

CREATE TABLE resource_type (
    id         uuid PRIMARY KEY,
    name       varchar(100) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Member kinds a resource of this type may contain (RESOURCE | GROUP | APPLICATION | USER),
-- enforced on attach. E.g. TEAM -> {GROUP, APPLICATION}, DEPT -> {RESOURCE}.
CREATE TABLE resource_type_allowed_member (
    type_id     uuid NOT NULL REFERENCES resource_type(id) ON DELETE CASCADE,
    member_type varchar(20) NOT NULL,
    PRIMARY KEY (type_id, member_type)
);

CREATE TABLE resource (
    id         uuid PRIMARY KEY,
    name       varchar(200) NOT NULL,
    type_id    uuid NOT NULL REFERENCES resource_type(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- DAG hierarchy edge (multi-parent M:N). Self-loops rejected here; longer cycles are rejected by the
-- domain layer (reachability check) before an edge is inserted.
CREATE TABLE resource_edge (
    parent_id uuid NOT NULL REFERENCES resource(id) ON DELETE CASCADE,
    child_id  uuid NOT NULL REFERENCES resource(id) ON DELETE CASCADE,
    PRIMARY KEY (parent_id, child_id),
    CONSTRAINT resource_edge_no_self_loop CHECK (parent_id <> child_id)
);
CREATE INDEX idx_resource_edge_child ON resource_edge (child_id);

-- Polymorphic leaf members (GROUP | APPLICATION | USER), M:N — an entity may belong to many
-- resources (full sharing). member_id is text to hold both uuids (users/groups) and application ids
-- (registered_client.id / saml_relying_party.id), mirroring app_assignment.app_id.
CREATE TABLE resource_member (
    resource_id uuid NOT NULL REFERENCES resource(id) ON DELETE CASCADE,
    member_type varchar(20) NOT NULL,
    member_id   varchar(255) NOT NULL,
    PRIMARY KEY (resource_id, member_type, member_id)
);
CREATE INDEX idx_resource_member_target ON resource_member (member_type, member_id);

-- Delegated administration: fixed tiers (ADMIN | VIEWER) now; nullable role_id reserves catalog-role
-- scoping (resource-scoped RBAC) for later.
CREATE TABLE resource_role (
    resource_id uuid NOT NULL REFERENCES resource(id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    tier        varchar(20) NOT NULL,
    role_id     uuid REFERENCES role(id) ON DELETE SET NULL,
    PRIMARY KEY (resource_id, user_id, tier)
);
CREATE INDEX idx_resource_role_user ON resource_role (user_id, tier);
