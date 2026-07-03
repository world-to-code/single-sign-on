-- IP access rules move from a global, pre-authentication table onto the session policy: each policy now
-- carries an ordered set of ALLOW/BLOCK CIDR rules, evaluated first-match against the client IP AFTER the
-- user's policy is resolved (post-authentication). The old global ip_rule table is removed.

CREATE TABLE session_policy_ip_rule (
    policy_id uuid        NOT NULL REFERENCES session_policy (id) ON DELETE CASCADE,
    cidr      varchar(64) NOT NULL,
    action    varchar(8)  NOT NULL,
    priority  integer     NOT NULL
);

CREATE INDEX idx_session_policy_ip_rule_policy ON session_policy_ip_rule (policy_id);

DROP TABLE IF EXISTS ip_rule;
