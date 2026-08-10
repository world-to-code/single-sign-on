package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.export.AuditExportRecord;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.audit.export.AuditExportTarget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the export does when the collector does not answer.
 *
 * <p>The property that matters is not "it retries" but WHERE the cursor is while it does: a batch that was
 * not acknowledged must still be the next batch. Advancing on send would turn one bad minute into a
 * permanent hole in the trail, and a hole in an audit trail cannot be seen from the outside.
 */
class AuditExportSweeperTest {

    private static final AuditExportTarget TARGET =
            new AuditExportTarget("https://collector.example.com/ingest", "bearer", false);
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 500;

    private AuditExportSettingsService settings;
    private AuditExportReader reader;
    private AuditExportDelivery delivery;
    private AuditService audit;
    private AuditExportSweeper sweeper;

    @BeforeEach
    void setUp() {
        reader = mock(AuditExportReader.class);
        delivery = mock(AuditExportDelivery.class);
        audit = mock(AuditService.class);
        settings = mock(AuditExportSettingsService.class);
        when(settings.target()).thenReturn(Optional.of(TARGET));
        sweeper = sweeperWithClock(Clock.systemUTC());
    }

    private AuditExportSweeper sweeperWithClock(Clock clock) {
        return new AuditExportSweeper(mock(StringRedisTemplate.class), settings, reader,
                new OcsfMapper("Mini SSO"), delivery, audit, clock,
                Duration.ofSeconds(30), Duration.ofSeconds(15), BATCH_SIZE, Duration.ofSeconds(25),
                Duration.ofSeconds(5), 2.0, 0.3, MAX_ATTEMPTS);
    }

    @Test
    void anAcknowledgedBatchAdvancesTheCursor() {
        AuditExportBatch batch = batchOfOne();
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batch);

        sweeper.export();

        verify(delivery).send(any(), any());
        verify(reader).commitCursor(batch);
    }

    /** The load-bearing one: a batch the collector never accepted stays the next batch. */
    @Test
    void aFailedDeliveryLeavesTheCursorWhereItWas() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());
        doThrow(new RestClientException("collector down")).when(delivery).send(any(), any());

        sweeper.export();

        verify(reader, never()).commitCursor(any());
    }

    /** Nothing to ship reaches no network and writes no row — most ticks are this one. */
    @Test
    void anEmptyBatchIsNotDelivered() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(AuditExportBatch.EMPTY);

        sweeper.export();

        verify(delivery, never()).send(any(), any());
        verify(audit, never()).record(any());
    }

    /**
     * A single failure is not an incident — collectors restart. The incident is an export that has STOPPED,
     * so the row is written when retrying stops being plausible, not on the first bad tick.
     */
    @Test
    void oneFailureIsNotYetRecordedAsAStoppedExport() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());
        doThrow(new RestClientException("collector down")).when(delivery).send(any(), any());

        sweeper.export();

        verify(audit, never()).record(any());
    }

    /**
     * Recorded LOCALLY, because the place that would otherwise be told is the collector that is not
     * receiving anything.
     */
    @Test
    void anExportThatHasGivenUpIsRecordedInTheLocalTrail() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());
        doThrow(new RestClientException("collector down")).when(delivery).send(any(), any());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            sweeper.export();
        }

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().type()).isEqualTo(AuditType.AUDIT_EXPORT_FAILED);
        assertThat(recorded.getValue().success()).isFalse();
    }

    /** A recovery clears the count, so a later blip does not inherit an old streak and alarm early. */
    @Test
    void aSuccessfulTickResetsTheFailureStreak() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());
        doThrow(new RestClientException("down")).when(delivery).send(any(), any());
        sweeper.export();
        sweeper.export();

        reset(delivery);            // the collector came back
        sweeper.export();
        doThrow(new RestClientException("down again")).when(delivery).send(any(), any());
        sweeper.export();

        verify(audit, never()).record(any());
    }

    /**
     * Nobody configured an export, so there is nothing to alarm about. This is the state that must stay
     * SILENT, and it is the reason a broken collector cannot simply be reported as "no target".
     */
    @Test
    void anExportNobodyAskedForIsNotAnIncident() {
        when(settings.target()).thenReturn(Optional.empty());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            sweeper.export();
        }

        verify(reader, never()).nextBatch(any(), anyBatchSize());
        verify(audit, never()).record(any());
    }

    /**
     * A collector that IS configured and can no longer be resolved — a repointed host, or a credential this
     * instance can no longer decrypt after a key rotation. It used to throw straight out of the scheduled
     * method, so the export stopped and the alarm designed to say so never ran.
     */
    @Test
    void aConfiguredCollectorThatCannotBeResolvedReachesTheAlarm() {
        when(settings.target()).thenThrow(new IllegalStateException("collector no longer valid"));

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            sweeper.export();
        }

        verify(audit).record(any());
    }

    /** A corrupt cursor is the same event to anyone relying on the trail: it is not arriving. */
    @Test
    void aCursorThatCannotBeReadReachesTheAlarm() {
        when(reader.nextBatch(any(), anyBatchSize())).thenThrow(new AuditExportCursorException("nonsense"));

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            sweeper.export();
        }

        verify(audit).record(any());
    }

    /**
     * One row this build cannot classify must not silence every other tenant's trail. The startup check proves
     * each ENUM CONSTANT is mapped; it says nothing about a stored string that is no longer a constant, which
     * is the direction that actually happens — a rollback, or a rolling deploy where a newer node wrote a type
     * this one has never heard of. Stopping there would hold up everybody's trail behind one row, for ever,
     * because the cursor cannot pass what will not map.
     */
    @Test
    void aRowWhoseTypeThisBuildDoesNotKnowStillShips() {
        AuditExportBatch batch = batchOf("A_TYPE_THIS_BUILD_NO_LONGER_HAS");
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batch);

        sweeper.export();

        verify(delivery).send(any(), any());
        verify(reader).commitCursor(batch);
        verify(audit, never()).record(any());
    }

    /** The give-up row carries the failure TYPE, never a message that could quote the batch back. */
    @Test
    void theGiveUpRowNamesTheCauseWithoutCopyingItsMessage() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());
        doThrow(new RestClientException("rejected record for a@example.com from 203.0.113.7"))
                .when(delivery).send(any(), any());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            sweeper.export();
        }

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().detail())
                .contains("RestClientException")
                .doesNotContain("a@example.com", "203.0.113.7");
    }

    /**
     * A full batch means more is waiting. Shipping one batch per tick made the CONFIGURATION the throughput
     * ceiling, so a deployment producing faster than that fell permanently behind while every indicator stayed
     * green — the collector receiving a steady stream that describes hours ago.
     */
    @Test
    void aFullBatchIsFollowedByTheNextOneWithinTheSameTick() {
        when(reader.nextBatch(any(), anyBatchSize()))
                .thenReturn(fullBatch())
                .thenReturn(fullBatch())
                .thenReturn(AuditExportBatch.EMPTY);

        sweeper.export();

        verify(delivery, times(2)).send(any(), any());
    }

    /**
     * The drain is bounded by wall clock, not only by the backlog. Without this the lock could be held past
     * its own TTL on a large backlog, and a second node would start shipping while the first still was.
     */
    @Test
    void drainingStopsWhenTheTickRunsOutOfTime() {
        sweeper = sweeperWithClock(clockAdvancing(Duration.ofSeconds(10)));
        // A backlog far larger than one tick can drain: the budget, not the backlog, has to be what stops it.
        when(reader.nextBatch(any(), anyBatchSize()))
                .thenReturn(fullBatch(), fullBatch(), fullBatch(), fullBatch(), fullBatch(),
                        fullBatch(), fullBatch(), fullBatch(), fullBatch(), fullBatch())
                .thenReturn(AuditExportBatch.EMPTY);

        sweeper.export();

        // 15s of budget against a clock that jumps 10s per read stops this after the second batch.
        verify(delivery, times(2)).send(any(), any());
    }

    /** A partial batch is the end of the backlog: stop, do not spin asking for more. */
    @Test
    void aPartialBatchEndsTheTick() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());

        sweeper.export();

        verify(delivery, times(1)).send(any(), any());
    }

    /** A clock that moves on every read, so a budget can actually expire inside a test. */
    private Clock clockAdvancing(Duration step) {
        Instant start = Instant.parse("2026-08-10T00:00:00Z");
        AtomicLong reads = new AtomicLong();
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return start.plus(step.multipliedBy(reads.getAndIncrement()));
            }
        };
    }

    /**
     * The redaction is applied on the way out, not left to the collector. A deployment whose configuring
     * admin does not hold audit:read:pii must not be able to obtain those fields by pointing the export
     * somewhere they can read.
     */
    @SuppressWarnings("unchecked")
    @Test
    void anExportWithoutThePiiGrantShipsNoIdentifiers() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());

        sweeper.export();

        Map<String, Object> actor = (Map<String, Object>) sentEvent().get("actor");
        assertThat((Map<String, Object>) actor.get("user")).containsEntry("email_addr", null);
        assertThat((Map<String, Object>) sentEvent().get("src_endpoint")).containsEntry("ip", "");
    }

    /** And the permitted deployment still gets the correlation axis, or the export is useless for XDR. */
    @SuppressWarnings("unchecked")
    @Test
    void anExportPermittedToCarryIdentifiersStillDoes() {
        settings = mock(AuditExportSettingsService.class);
        when(settings.target()).thenReturn(Optional.of(
                new AuditExportTarget("https://collector.example.com/ingest", "bearer", true)));
        sweeper = sweeperWithClock(Clock.systemUTC());
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());

        sweeper.export();

        Map<String, Object> actor = (Map<String, Object>) sentEvent().get("actor");
        assertThat((Map<String, Object>) actor.get("user")).containsEntry("email_addr", "a@example.com");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentEvent() {
        ArgumentCaptor<List<Map<String, Object>>> sent = ArgumentCaptor.forClass(List.class);
        verify(delivery).send(any(), sent.capture());
        return sent.getValue().get(0);
    }

    private int anyBatchSize() {
        return anyInt();
    }

    private AuditExportBatch batchOfOne() {
        return batchOf(AuditType.AUTH_SUCCESS.name());
    }

    /** A batch at the size limit — the shape that says "there is more behind me". */
    private AuditExportBatch fullBatch() {
        List<AuditExportRecord> rows = IntStream.rangeClosed(1, BATCH_SIZE).mapToObj(this::rowNumbered).toList();
        AuditExportRecord last = rows.get(rows.size() - 1);
        return new AuditExportBatch(rows, last.occurredAt(), last.id());
    }

    private AuditExportRecord rowNumbered(int id) {
        return new AuditExportRecord(id, Instant.now(), AuditType.AUTH_SUCCESS.name(), "AUTHENTICATION",
                "someone", true, null, null, "INFO", "USER", UUID.randomUUID(), "a@example.com", "A", "NONE",
                null, "203.0.113.7", null, null, null, UUID.randomUUID());
    }

    private AuditExportBatch batchOf(String type) {
        AuditExportRecord row = new AuditExportRecord(1L, Instant.now(), type,
                "AUTHENTICATION", "someone", true, null, null, "INFO", "USER", UUID.randomUUID(),
                "a@example.com", "A", "NONE", null, "203.0.113.7", null, null, null, UUID.randomUUID());
        return new AuditExportBatch(List.of(row), row.occurredAt(), row.id());
    }
}
