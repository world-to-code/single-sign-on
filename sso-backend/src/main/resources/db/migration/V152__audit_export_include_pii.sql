-- Whether the export carries the actor's and client's personal identifiers.
--
-- The console already gates these behind audit:read:pii: without that grant, AuditEntry.withoutPii nulls the
-- actor email, display name and account id, and the client IP, User-Agent, device and correlation id, so a
-- redacted row says neither who exactly nor from where and cannot be joined to other rows by id.
--
-- The export shipped all seven unconditionally, and audit:export is deliberately excluded from the implied
-- audit:read expansion — so its holder need not have audit:read at all, let alone audit:read:pii. Pointing the
-- collector at a URL they control turned "may ship logs" into an un-redacted, cross-tenant identity feed.
--
-- DEFAULT false, so an existing deployment stops sending PII on upgrade rather than continuing to and being
-- told about it later. Turning it on requires audit:read:pii in addition to audit:export.
ALTER TABLE audit_export_settings
    ADD COLUMN include_pii boolean NOT NULL DEFAULT false;
