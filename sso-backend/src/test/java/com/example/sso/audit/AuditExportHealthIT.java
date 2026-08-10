package com.example.sso.audit;

import com.example.sso.audit.export.AuditExportHealth;
import com.example.sso.audit.export.AuditExportSettings;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether anyone can tell that the export has fallen behind.
 *
 * <p>The give-up audit row only ever fires when a DELIVERY fails, which leaves the worst case unreported: a
 * collector accepting every batch while the backlog grows produces no failures at all, so the export reports
 * success while the SIEM describes hours ago. Nothing in the system could answer "how far behind is it" — no
 * metric, no health indicator, and a settings view that carried only the configuration.
 */
class AuditExportHealthIT extends AbstractIntegrationTest {

    private static final String FAILURES_KEY = "sso:audit:export:failures";
    private static final String LAST_SUCCESS_KEY = "sso:audit:export:last-success";

    @Autowired
    AuditExportSettingsService settings;
    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void configureCollector() {
        settings.save(new AuditExportSettings("https://one.one.one.one/ingest", "collector-bearer", true, false));
        clearStatus();
    }

    @AfterEach
    void cleanup() {
        ownerJdbc().update("delete from audit_export_settings");
        clearStatus();
    }

    /** The headline number: a stale collector is visible as staleness, not merely as an absence of errors. */
    @Test
    void aBacklogIsReportedAsHowFarBehindTheCollectorIs() {
        setCursor(Instant.now().minus(2, ChronoUnit.HOURS));

        AuditExportHealth health = settings.current().orElseThrow().health();

        assertThat(health.behindBySeconds())
                .as("two hours of backlog, while every delivery was succeeding")
                .isBetween(7000L, 7400L);
    }

    /** The streak is read from where the fleet shares it, not from whichever node answered the request. */
    @Test
    void theReportedFailureStreakIsTheFleetsNotThisNodes() {
        redis.opsForValue().set(FAILURES_KEY, "4");

        assertThat(settings.current().orElseThrow().health().consecutiveFailures()).isEqualTo(4);
    }

    @Test
    void anExportThatHasNeverShippedSaysSoRatherThanClaimingItIsUpToDate() {
        AuditExportHealth health = settings.current().orElseThrow().health();

        assertThat(health.position()).isNull();
        assertThat(health.behindBySeconds()).as("unknown, not zero").isNull();
        assertThat(health.lastSuccessAt()).isNull();
    }

    /**
     * A cursor the reader refuses must not take the screen down with it. The refusal is what stops the export
     * and raises the alarm; the health view's job is to still render, so an operator can see the streak
     * climbing beside a position it cannot vouch for.
     */
    @Test
    void aRefusedCursorStillRenders() {
        setCursor(Instant.now().plus(1, ChronoUnit.DAYS));

        AuditExportHealth health = settings.current().orElseThrow().health();

        assertThat(health.position()).isNull();
    }

    private void setCursor(Instant position) {
        ownerJdbc().update("insert into audit_export_cursor (id, occurred_at, event_id) values (1, ?, 1)"
                + " on conflict (id) do update set occurred_at = excluded.occurred_at", Timestamp.from(position));
    }

    private void clearStatus() {
        ownerJdbc().update("delete from audit_export_cursor");
        redis.delete(FAILURES_KEY);
        redis.delete(LAST_SUCCESS_KEY);
    }
}
