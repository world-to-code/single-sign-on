package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportRecord;
import java.time.Instant;
import java.util.List;

/**
 * One tick's worth of events, together with the cursor position that reading them WOULD advance to.
 *
 * <p>The position travels with the batch rather than being remembered by the reader, because it must only be
 * stored once the collector has acknowledged delivery — a cursor advanced on read turns a failed POST into a
 * permanent hole in the trail.
 */
public record AuditExportBatch(List<AuditExportRecord> events, Instant nextTime, Long nextId) {

    static final AuditExportBatch EMPTY = new AuditExportBatch(List.of(), null, null);

    boolean isEmpty() {
        return events.isEmpty();
    }
}
