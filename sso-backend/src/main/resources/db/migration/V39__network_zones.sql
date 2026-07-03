-- Reusable named IP "network zones" (Okta-style): a zone is a name + description + a set of CIDRs, defined
-- once and referenced by session policies. Policy IP rules now reference a zone by id instead of inlining a
-- CIDR. (V38's session_policy_ip_rule is unreleased, so it is recreated rather than data-migrated.)

CREATE TABLE network_zone (
    id          uuid         NOT NULL PRIMARY KEY,
    name        varchar(100) NOT NULL UNIQUE,
    description varchar(255),
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE network_zone_cidr (
    zone_id uuid        NOT NULL REFERENCES network_zone (id) ON DELETE CASCADE,
    cidr    varchar(64) NOT NULL
);

CREATE INDEX idx_network_zone_cidr_zone ON network_zone_cidr (zone_id);

DROP TABLE IF EXISTS session_policy_ip_rule;

CREATE TABLE session_policy_ip_rule (
    policy_id uuid       NOT NULL REFERENCES session_policy (id) ON DELETE CASCADE,
    zone_id   uuid       NOT NULL REFERENCES network_zone (id),
    action    varchar(8) NOT NULL,
    priority  integer    NOT NULL
);

CREATE INDEX idx_session_policy_ip_rule_policy ON session_policy_ip_rule (policy_id);
