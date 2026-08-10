package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p>The cursor lives in Redis rather than in a node, for the reason the other sweepers' counters do:
 * consecutive ticks can be driven by different nodes.
 */
@Component
@RequiredArgsConstructor
public class AuditExportReader {

    /**
     * Cursor position as {@code <occurred_at epoch MICROS>:<id>}.
     *
     * <p>Microseconds, not millis: {@code timestamptz} keeps microsecond precision, so a millisecond cursor
     * rounds DOWN and re-offers every row inside the same millisecond on the next tick — the batch is
     * delivered again for ever, and an operator watching duplicates has no way to tell that from a retry.
     */
    static final String CURSOR_KEY = "sso:audit:export:cursor";

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
    private final StringRedisTemplate redis;

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
            redis.opsForValue().set(CURSOR_KEY, micros(batch.nextTime()) + ":" + batch.nextId());
        }
    }

    /** Rewinds to the beginning — for tests, and for an operator deliberately re-shipping history. */
    public void resetCursor() {
        redis.delete(CURSOR_KEY);
    }

    /**
     * The stored position, or the beginning when there is none. Every other value is checked rather than
     * trusted: this is a shared Redis key, and a cursor is the one piece of state that decides which events
     * are never looked at again.
     */
    private Cursor cursor() {
        String stored = redis.opsForValue().get(CURSOR_KEY);
        if (stored == null) {
            return new Cursor(Instant.EPOCH, 0L);
        }
        int separator = stored.lastIndexOf(':');
        if (separator < 1) {
            throw new AuditExportCursorException(stored);
        }
        Cursor cursor = parse(stored, separator);
        // A position later than now cannot have been reached. Left unchecked it is silent: the range query
        // matches nothing for ever, and an export that ships nothing looks exactly like one with nothing to ship.
        if (cursor.occurredAt().isAfter(Instant.now())) {
            throw new AuditExportCursorException(stored);
        }
        return cursor;
    }

    private Cursor parse(String stored, int separator) {
        try {
            long storedMicros = Long.parseLong(stored.substring(0, separator));
            return new Cursor(Instant.EPOCH.plus(storedMicros, ChronoUnit.MICROS),
                    Long.parseLong(stored.substring(separator + 1)));
        } catch (RuntimeException malformed) {
            throw new AuditExportCursorException(stored);
        }
    }

    /** The instant as whole microseconds, the resolution {@code timestamptz} actually stores. */
    private long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
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
