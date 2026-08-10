package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.export.AuditExportRecord;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.audit.export.AuditExportTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
            new AuditExportTarget("https://collector.example.com/ingest", "bearer");
    private static final int MAX_ATTEMPTS = 3;

    private AuditExportReader reader;
    private AuditExportDelivery delivery;
    private AuditService audit;
    private AuditExportSweeper sweeper;

    @BeforeEach
    void setUp() {
        reader = mock(AuditExportReader.class);
        delivery = mock(AuditExportDelivery.class);
        audit = mock(AuditService.class);
        AuditExportSettingsService settings = mock(AuditExportSettingsService.class);
        when(settings.target()).thenReturn(Optional.of(TARGET));
        sweeper = new AuditExportSweeper(mock(StringRedisTemplate.class), settings, reader,
                new OcsfMapper("Mini SSO"), delivery, audit,
                Duration.ofSeconds(30), 500, Duration.ofMinutes(2),
                Duration.ofSeconds(5), 2.0, 0.3, MAX_ATTEMPTS);
    }

    @Test
    void anAcknowledgedBatchAdvancesTheCursor() {
        AuditExportBatch batch = batchOfOne();
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batch);

        sweeper.export(TARGET);

        verify(delivery).send(any(), any());
        verify(reader).commitCursor(batch);
    }

    /** The load-bearing one: a batch the collector never accepted stays the next batch. */
    @Test
    void aFailedDeliveryLeavesTheCursorWhereItWas() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(batchOfOne());
        doThrow(new RestClientException("collector down")).when(delivery).send(any(), any());

        sweeper.export(TARGET);

        verify(reader, never()).commitCursor(any());
    }

    /** Nothing to ship reaches no network and writes no row — most ticks are this one. */
    @Test
    void anEmptyBatchIsNotDelivered() {
        when(reader.nextBatch(any(), anyBatchSize())).thenReturn(AuditExportBatch.EMPTY);

        sweeper.export(TARGET);

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

        sweeper.export(TARGET);

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
            sweeper.export(TARGET);
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
        sweeper.export(TARGET);
        sweeper.export(TARGET);

        reset(delivery);            // the collector came back
        sweeper.export(TARGET);
        doThrow(new RestClientException("down again")).when(delivery).send(any(), any());
        sweeper.export(TARGET);

        verify(audit, never()).record(any());
    }

    private int anyBatchSize() {
        return anyInt();
    }

    private AuditExportBatch batchOfOne() {
        AuditExportRecord row = new AuditExportRecord(1L, Instant.now(), AuditType.AUTH_SUCCESS.name(),
                "AUTHENTICATION", "someone", true, null, null, "INFO", "USER", UUID.randomUUID(),
                "a@example.com", "A", "NONE", null, "203.0.113.7", null, null, null, UUID.randomUUID());
        return new AuditExportBatch(List.of(row), row.occurredAt(), row.id());
    }
}
