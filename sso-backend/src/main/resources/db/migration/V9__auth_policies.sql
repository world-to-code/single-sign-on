-- Authentication policies: ordered factor chains assigned to users/roles, with priority.
CREATE TABLE auth_policy (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(100) NOT NULL UNIQUE,
    priority   int NOT NULL DEFAULT 0,
    enabled    boolean NOT NULL DEFAULT TRUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth_policy_step (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id  UUID NOT NULL REFERENCES auth_policy(id) ON DELETE CASCADE,
    step_order int NOT NULL
);

CREATE TABLE auth_policy_step_factor (
    step_id UUID NOT NULL REFERENCES auth_policy_step(id) ON DELETE CASCADE,
    factor  varchar(20) NOT NULL,
    PRIMARY KEY (step_id, factor)
);

CREATE TABLE auth_policy_user (
    policy_id UUID NOT NULL REFERENCES auth_policy(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL,
    PRIMARY KEY (policy_id, user_id)
);

CREATE TABLE auth_policy_role (
    policy_id UUID NOT NULL REFERENCES auth_policy(id) ON DELETE CASCADE,
    role_id   UUID NOT NULL,
    PRIMARY KEY (policy_id, role_id)
);
