-- Who last configured a connector.
--
-- A connector designates an authoritative source of identity ATTRIBUTES, and auto-mapping can turn an attribute
-- into a role or group grant. That makes controlling a connector a way to satisfy someone else's mapping rule
-- without holding any authority over its target: the existing dominance checks all validate the RULE'S AUTHOR,
-- never whoever caused a user to match. Recording the configurator is what lets the grant path ask that second
-- question. NULL means "unknown", which the evaluator treats as unauthorized — deliberately fail-closed.
ALTER TABLE directory_connector ADD COLUMN configured_by uuid;

COMMENT ON COLUMN directory_connector.configured_by IS
    'The administrator who last saved this connector; NULL fails closed for privilege-granting mapping targets.';

-- The evaluator resolves connectors from the attribute keys they fill, per tier.
CREATE INDEX IF NOT EXISTS idx_directory_mapping_target
    ON directory_attribute_mapping (org_id, target_key);
