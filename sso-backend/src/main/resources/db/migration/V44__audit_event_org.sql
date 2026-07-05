-- Tag audit events with the tenant they occurred in, for per-org analytics. Nullable: events with no
-- resolvable tenant (pre-tenant-resolution, platform/system actions) stay null. This is an analytics
-- DIMENSION, not an RLS discriminator — audit_event stays platform-readable and is NOT org-isolated (no
-- FK/cascade, so the trail outlives a deleted org). Old rows are null (trends start at rollout; not
-- backfilled). Indexed by (org_id, occurred_at) for the time-bucketed aggregation.
ALTER TABLE audit_event ADD COLUMN org_id uuid;
CREATE INDEX idx_audit_event_org_time ON audit_event (org_id, occurred_at);
