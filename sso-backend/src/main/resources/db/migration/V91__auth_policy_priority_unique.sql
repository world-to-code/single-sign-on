-- Auth-policy priority is UNIQUE within a tier (each tenant's own set + the global set). The login resolution
-- breaks a same-specificity tie on the policy's priority, so two policies sharing a priority in one tier would
-- make the winner non-deterministic (falling to an arbitrary id order).
-- AuthPolicyAdminServiceImpl.requirePriorityAvailable rejects a duplicate on create/update with a 409; these
-- partial unique indexes are the concurrency backstop for two writes racing the same priority — mirroring the
-- name uniqueness in V46. The seeded Defaults occupy reserved priorities (global 0, per-org 1), one per tier,
-- so no existing row collides.
CREATE UNIQUE INDEX uq_auth_policy_priority_global ON auth_policy (priority) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_auth_policy_org_priority    ON auth_policy (org_id, priority) WHERE org_id IS NOT NULL;
