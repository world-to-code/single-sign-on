-- Okta/SIEM-grade enrichment of the audit trail. Additive columns: the structured actor identity,
-- the client context (user-agent/device/correlation id), a structured outcome reason, and a triage
-- severity. audit_event stays RLS-free (org_id is an analytics dimension, unchanged).
--
-- Nullability: actor/client/reason columns are nullable — historical rows predate enrichment,
-- non-user actors (service/system) carry no account, and off-request recorders (async sweeps,
-- AFTER_COMMIT listeners) have no client context. severity is NOT NULL with a neutral INFO floor:
-- it is a required triage dimension the writer always sets; legacy rows default to INFO.

ALTER TABLE audit_event
    ADD COLUMN actor_type    varchar(16),
    ADD COLUMN actor_id      uuid,
    ADD COLUMN actor_email   varchar(255),
    ADD COLUMN actor_display varchar(255),
    ADD COLUMN user_agent    text,
    ADD COLUMN device        varchar(128),
    ADD COLUMN request_id    varchar(64),
    ADD COLUMN reason        varchar(500),
    ADD COLUMN severity      varchar(16) NOT NULL DEFAULT 'INFO';

-- "All events by this account" (SIEM per-user timeline / anomaly baseline).
CREATE INDEX idx_audit_event_actor_id ON audit_event (actor_id);

-- "All WARNING/CRITICAL events since T" (SIEM triage / alerting).
CREATE INDEX idx_audit_event_severity_time ON audit_event (severity, occurred_at);
