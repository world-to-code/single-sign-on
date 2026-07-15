-- Metadata-driven auto-mapping: a rule "users where attr_key = attr_value → add to group group_id", re-evaluated
-- when a user's attributes change. Org-scoped (a tenant's rule only ever touches its own users/groups), RLS +
-- tier-aware uniqueness like V93 (entity_attribute). group_id is a plain uuid (no cross-module FK — the mapping
-- module validates the group via UserGroupService and cleans up on GroupDeletedEvent, mirroring entity_attribute).
CREATE TABLE mapping_rule (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    attr_key    varchar(64)  NOT NULL,
    attr_value  varchar(255) NOT NULL,
    then_kind   varchar(16)  NOT NULL,   -- GROUP (ROLE | RESOURCE_MEMBER are later kinds)
    group_id    uuid         NOT NULL,   -- the target group (validated in the app, not FK'd cross-module)
    org_id      uuid         REFERENCES organization (id) ON DELETE CASCADE,  -- NULL = global (platform)
    created_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT mapping_rule_then_kind CHECK (then_kind IN ('GROUP'))
);

-- No duplicate identical rule within a tier (a global rule and a tenant's own may coincide).
CREATE UNIQUE INDEX uq_mapping_rule_global
    ON mapping_rule (attr_key, attr_value, then_kind, group_id) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_mapping_rule_org
    ON mapping_rule (org_id, attr_key, attr_value, then_kind, group_id) WHERE org_id IS NOT NULL;
-- Re-evaluate the rules matching a changed attribute (org + key + value).
CREATE INDEX idx_mapping_rule_lookup ON mapping_rule (org_id, attr_key, attr_value);
CREATE INDEX idx_mapping_rule_group ON mapping_rule (group_id);

ALTER TABLE mapping_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE mapping_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON mapping_rule
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));

-- Provenance: exactly the memberships a rule materialized, so retract (rule deleted, or a user stops matching)
-- removes only rule-managed rows and never a manually-added member. rule_id is a same-module FK (CASCADE is a
-- backstop; the service retracts the group memberships before deleting the rule).
CREATE TABLE mapping_rule_membership (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id    uuid        NOT NULL REFERENCES mapping_rule (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    group_id   uuid        NOT NULL,   -- = the rule's target group, denormalized for retract queries
    org_id     uuid        REFERENCES organization (id) ON DELETE CASCADE,   -- NULL = global
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_mapping_rule_membership UNIQUE (rule_id, user_id)  -- rule_id is globally unique, so no tier split
);

CREATE INDEX idx_mapping_rule_membership_rule ON mapping_rule_membership (rule_id);
CREATE INDEX idx_mapping_rule_membership_claim ON mapping_rule_membership (user_id, group_id);
CREATE INDEX idx_mapping_rule_membership_org ON mapping_rule_membership (org_id); -- supports the org_id FK cascade

ALTER TABLE mapping_rule_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE mapping_rule_membership FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON mapping_rule_membership
    USING (current_setting('app.platform', true) = 'on'
           OR org_id IS NULL
           OR org_id::text = current_setting('app.current_org', true))
    WITH CHECK (current_setting('app.platform', true) = 'on'
               OR org_id::text = current_setting('app.current_org', true)
               OR (org_id IS NULL AND coalesce(current_setting('app.current_org', true), '') = ''));
