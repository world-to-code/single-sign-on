package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the audit trail forward for the collector, without losing rows.
 *
 * <p><b>The trap this exists for.</b> An {@code id}- or {@code occurred_at}-ordered cursor SILENTLY LOSES
 * ROWS. Both look monotonic and neither is: {@code id} is allocated at INSERT, so a transaction holding 98
 * can commit after one holding 99; and Postgres' {@code now()} is TRANSACTION-START time, so a long
 * transaction stamps a moment earlier than rows written and committed while it ran. A reader that has
 * advanced past 99 will never see 98 — the row is not delayed, it is gone, permanently, with nothing to
 * indicate a gap. For an audit trail that is the worst possible failure: the export looks healthy and is
 * quietly incomplete.
 *
 * <p><b>The fix is a lag window.</b> Only rows older than the configured lag are eligible, and the cursor is
 * the TUPLE {@code (occurred_at, id)}. Any transaction still in flight at time T commits well within the lag,
 * so by the time T becomes exportable every row stamped T is visible. The bound is small here by
 * construction: {@code AuditService.record} runs {@code REQUIRES_NEW}, i.e. a single short INSERT that never
 * inherits the caller's transaction. The cost is a fixed delivery delay, which is the correct trade — a SIEM
 * alerting thirty seconds late is fine, a SIEM missing the one event that mattered is not.
 *
 * <p>This makes the reader COMPLETE, not exactly-once: a delivery that fails after the collector accepted it
 * is retried, so the collector must tolerate duplicates and deduplicate on the record id.
 *
 * <p>The cursor lives in Postgres, not in a node and not in Redis. Consecutive ticks can be driven by
 * different nodes, so it cannot be local; and Redis here has no volume, so a restart would drop it and the
 * reader would start at the beginning — re-shipping the whole trail and, far worse, leaving the export weeks
 * behind the present on a table of any size while every indicator still reported success.
 */
@Component
@RequiredArgsConstructor
public class AuditExportReader {

    private static final String READ_CURSOR =
            "SELECT occurred_at, event_id FROM audit_export_cursor WHERE id = 1";

    /** One row, so advancing is an upsert rather than a check-then-write two nodes could interleave. */
    private static final String WRITE_CURSOR = """
            INSERT INTO audit_export_cursor (id, occurred_at, event_id, updated_at)
            VALUES (1, ?, ?, now())
            ON CONFLICT (id) DO UPDATE SET occurred_at = EXCLUDED.occurred_at,
                                           event_id = EXCLUDED.event_id,
                                           updated_at = now()
            """;

    private static final String NEXT_BATCH = """
            SELECT id, occurred_at, type, category, principal, success, detail, reason, severity,
                   actor_type, actor_id, actor_email, actor_display,
                   subject_type, subject_id, remote_ip, user_agent, device, request_id, org_id
              FROM audit_event
             WHERE (occurred_at, id) > (?, ?)
               AND occurred_at <= ?
             ORDER BY occurred_at, id
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    /**
     * The next events to ship, oldest first, or an empty batch when there are none yet eligible.
     *
     * <p>Does NOT advance the cursor — see {@link #commitCursor}.
     */
    public AuditExportBatch nextBatch(Duration lag, int batchSize) {
        Cursor cursor = cursor();
        Instant ceiling = Instant.now().minus(lag);
        List<AuditExportRecord> events = jdbc.query(NEXT_BATCH, this::toRecord,
                Timestamp.from(cursor.occurredAt()), cursor.id(), Timestamp.from(ceiling), batchSize);
        if (events.isEmpty()) {
            return AuditExportBatch.EMPTY;
        }
        AuditExportRecord last = events.get(events.size() - 1);
        return new AuditExportBatch(events, last.occurredAt(), last.id());
    }

    /**
     * Advances past a batch the collector has ACKNOWLEDGED.
     *
     * <p>Separate from reading on purpose: advancing on read would turn one failed POST into a permanent hole
     * in the trail, and a hole in an audit trail is invisible from the outside.
     */
    public void commitCursor(AuditExportBatch batch) {
        if (!batch.isEmpty()) {
            jdbc.update(WRITE_CURSOR, Timestamp.from(batch.nextTime()), batch.nextId());
        }
    }

    /**
     * The timestamp of the newest event the collector has, or empty before the first batch.
     *
     * <p>Answers empty rather than throwing on a corrupt cursor: this feeds a health view, and a screen that
     * fails to render is a worse answer than one reporting an unknown position beside a failure streak that
     * is already climbing.
     */
    public Optional<Instant> position() {
        try {
            Cursor cursor = cursor();
            return cursor.occurredAt().equals(Instant.EPOCH) ? Optional.empty() : Optional.of(cursor.occurredAt());
        } catch (AuditExportCursorException unreadable) {
            return Optional.empty();
        }
    }

    /** Rewinds to the beginning — for tests, and for an operator deliberately re-shipping history. */
    public void resetCursor() {
        jdbc.update("DELETE FROM audit_export_cursor");
    }

    /**
     * The stored position, or the beginning when there is none.
     *
     * <p>Still checked rather than trusted, even now that the columns are typed. A position later than now
     * cannot have been reached, and left unchecked it is the silent shape: the range query matches nothing for
     * ever, and an export shipping nothing looks exactly like one with nothing to ship.
     */
    private Cursor cursor() {
        List<Cursor> stored = jdbc.query(READ_CURSOR,
                (row, number) -> new Cursor(row.getTimestamp("occurred_at").toInstant(), row.getLong("event_id")));
        if (stored.isEmpty()) {
            return new Cursor(Instant.EPOCH, 0L);
        }
        Cursor cursor = stored.get(0);
        if (cursor.occurredAt().isAfter(Instant.now())) {
            throw new AuditExportCursorException(cursor.occurredAt() + ":" + cursor.id());
        }
        return cursor;
    }

    private AuditExportRecord toRecord(ResultSet row, int rowNumber) throws SQLException {
        return new AuditExportRecord(
                row.getLong("id"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getString("type"),
                row.getString("category"),
                row.getString("principal"),
                row.getBoolean("success"),
                row.getString("detail"),
                row.getString("reason"),
                row.getString("severity"),
                row.getString("actor_type"),
                row.getObject("actor_id", UUID.class),
                row.getString("actor_email"),
                row.getString("actor_display"),
                row.getString("subject_type"),
                row.getString("subject_id"),
                row.getString("remote_ip"),
                row.getString("user_agent"),
                row.getString("device"),
                row.getString("request_id"),
                row.getObject("org_id", UUID.class));
    }

    /** Where the last acknowledged delivery ended: the tuple the next read is strictly greater than. */
    private record Cursor(Instant occurredAt, long id) {
    }
}
