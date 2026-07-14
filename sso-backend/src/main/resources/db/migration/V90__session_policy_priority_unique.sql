-- Session-policy priority is UNIQUE within a tier (each tenant's own set + the global set). The session
-- resolution breaks a same-specificity tie on the policy's priority, so two policies sharing a priority in one
-- tier would make the winner non-deterministic (falling to an arbitrary id order).
-- SessionPolicyServiceImpl.requirePriorityAvailable rejects a duplicate on create/update with a 409; these
-- partial unique indexes are the concurrency backstop for two writes racing the same priority — mirroring the
-- name uniqueness in V47. The seeded Defaults occupy reserved priorities (global 0, per-org 1), one per tier,
-- so no existing row collides.
CREATE UNIQUE INDEX uq_session_policy_priority_global ON session_policy (priority) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_session_policy_org_priority    ON session_policy (org_id, priority) WHERE org_id IS NOT NULL;
