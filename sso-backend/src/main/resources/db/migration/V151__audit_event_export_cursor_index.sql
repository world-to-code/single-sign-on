-- The export reader's cursor index.
--
-- The reader walks (occurred_at, id) as a TUPLE — `WHERE (occurred_at, id) > (?, ?)` — because neither
-- column alone is monotonic: `id` is allocated at INSERT (a transaction holding 98 can commit after one
-- holding 99) and `now()` is TRANSACTION-START time. The existing idx_audit_event_occurred_at cannot serve
-- that comparison as a range scan; a composite index on both columns can.
--
-- Without it the exporter degrades to a full scan of the audit table on every tick, on the hot table every
-- authentication writes to.
CREATE INDEX idx_audit_event_export_cursor ON audit_event (occurred_at, id);
