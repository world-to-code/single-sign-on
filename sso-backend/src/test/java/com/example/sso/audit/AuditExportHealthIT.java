package com.example.sso.audit;

import com.example.sso.audit.export.AuditExportHealth;
import com.example.sso.audit.export.AuditExportSettings;
import com.example.sso.audit.export.AuditExportSettingsService;
import com.example.sso.support.AbstractIntegrationTest;
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

    private static final String CURSOR_KEY = "sso:audit:export:cursor";
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
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        redis.opsForValue().set(CURSOR_KEY, ChronoUnit.MICROS.between(Instant.EPOCH, twoHoursAgo) + ":1");

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

    /** A corrupt cursor must not take the screen down with it — the streak beside it is the real signal. */
    @Test
    void anUnreadableCursorStillRenders() {
        redis.opsForValue().set(CURSOR_KEY, "nonsense");

        AuditExportHealth health = settings.current().orElseThrow().health();

        assertThat(health.position()).isNull();
    }

    private void clearStatus() {
        redis.delete(CURSOR_KEY);
        redis.delete(FAILURES_KEY);
        redis.delete(LAST_SUCCESS_KEY);
    }
}
