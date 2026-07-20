-- The run history is read by connector alone (DirectorySyncRunRepository.findByConnectorIdOrderByStartedAtDesc)
-- and cascaded by connector alone (directory_sync_run.connector_id REFERENCES directory_connector ON DELETE
-- CASCADE). The existing (org_id, connector_id, started_at DESC) index serves neither: a composite index does
-- not support a predicate on a non-leading column, so both the admin history page and every connector deletion
-- were sequential-scanning the whole table.
create index if not exists idx_directory_sync_run_history
    on directory_sync_run (connector_id, started_at desc);

drop index if exists idx_directory_sync_run_connector;
