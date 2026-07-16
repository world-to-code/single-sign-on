-- Record the authoring admin on a mapping rule so the async materialize can re-validate, at grant time, that
-- the author STILL holds the authority the create/update gate demanded (zero-trust: authority is not frozen at
-- authoring). Nullable: pre-V97 rows and any system-provisioned rule have no author — those are allowed but
-- audited (MAPPING_RULE_LEGACY_AUTHOR). No cross-module FK to app_user (the id is app-validated, mirroring
-- target_id/org_id). No index: created_by is read only while re-validating a rule already in hand.
ALTER TABLE mapping_rule ADD COLUMN created_by uuid;

COMMENT ON COLUMN mapping_rule.created_by IS 'authoring admin user id; NULL = legacy (pre-V97) or system rule';
