-- Allow a mapping rule to target a resource's membership (add a matching user as a resource member), alongside
-- GROUP and ROLE. Only the then_kind CHECK widens; target_id already holds the resource id (a plain uuid,
-- app-validated, like group/role targets). A rule's resource-member rows are cleaned up when the rule is
-- deleted (provenance) or when the resource is deleted (ResourceDeletedEvent -> MappingTargetDeletionListener).
ALTER TABLE mapping_rule DROP CONSTRAINT mapping_rule_then_kind;
ALTER TABLE mapping_rule ADD CONSTRAINT mapping_rule_then_kind
    CHECK (then_kind IN ('GROUP', 'ROLE', 'RESOURCE_MEMBER'));
