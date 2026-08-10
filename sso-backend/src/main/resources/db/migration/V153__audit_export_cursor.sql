-- How far the export has got, moved out of Redis.
--
-- Redis is a cache here: neither compose file mounts a volume for it, so a restart drops the key. The reader
-- treats a missing cursor as "start at the beginning", which re-ships the entire trail to the collector and —
-- far worse — puts the export weeks behind on a table of any size, while every indicator reports success. The
-- collector deduplicates on the record id so nothing is corrupted; it is simply blind to the present until it
-- has replayed the past.
--
-- The position also stops being a parsed string. It was <epoch micros>:<id>, which meant hand-written
-- microsecond arithmetic (a millisecond version silently re-offered every row inside the same millisecond,
-- for ever) and a parse that could fail on a value nothing validated. Native timestamptz + bigint removes
-- that whole class: the tuple is stored as the same two types the ORDER BY compares.
--
-- One row, like the settings it belongs beside, but its OWN table: re-pointing a collector must not reset the
-- position, and clearing the configuration must not silently rewind the trail.
CREATE TABLE audit_export_cursor (
    id           SMALLINT    PRIMARY KEY CHECK (id = 1),   -- exactly one, enforced
    occurred_at  timestamptz NOT NULL,
    event_id     bigint      NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
