package com.example.sso.audit;

import com.example.sso.audit.export.AuditExportRecord;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two exits an audit row can leave by, and the one rule that governs both.
 *
 * <p>The console redacts seven fields for a reader without {@code audit:read:pii}. The export shipped all
 * seven unconditionally, to a destination its own holder chooses, across every tenant — so the screen's rule
 * was enforced on the screen and nowhere else. These are two implementations of one decision, which is exactly
 * the shape that drifts, so the agreement between them is asserted rather than assumed.
 *
 * <p>What stays is as deliberate as what goes: the coarse actor TYPE survives both, because losing it would
 * flatten the claim-versus-identity distinction — the one piece of actor information that reveals nothing and
 * that a collector most needs.
 */
class AuditPiiRedactionTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void theExportDropsTheSameIdentifiersTheConsoleDoes() {
        AuditEntry console = fullEntry().withoutPii();
        AuditExportRecord exported = fullRecord().withoutPii();

        assertThat(console.actorId()).isNull();
        assertThat(exported.actorId()).as("the stable id that joins events to a person").isNull();

        assertThat(console.actorEmail()).isNull();
        assertThat(exported.actorEmail()).isNull();

        assertThat(console.actorDisplay()).isNull();
        assertThat(exported.actorDisplay()).isNull();

        assertThat(console.remoteIp()).isNull();
        assertThat(exported.remoteIp()).as("where from").isNull();

        assertThat(console.userAgent()).isNull();
        assertThat(exported.userAgent()).isNull();

        assertThat(console.device()).isNull();
        assertThat(exported.device()).isNull();

        assertThat(console.requestId()).isNull();
        assertThat(exported.requestId()).as("the cross-event correlator").isNull();
    }

    /** A redacted row still has to be worth shipping, or the redacted mode is just a broken export. */
    @Test
    void whatRemainsIsStillAnEvent() {
        AuditExportRecord exported = fullRecord().withoutPii();

        assertThat(exported.id()).isEqualTo(42L);
        assertThat(exported.type()).isEqualTo(AuditType.AUTH_FAILURE.name());
        assertThat(exported.success()).isFalse();
        assertThat(exported.occurredAt()).isNotNull();
        assertThat(exported.orgId()).as("the tenant, or a collector index cannot separate them").isEqualTo(ORG_ID);
        assertThat(exported.actorType()).as("claim versus identity survives redaction").isEqualTo("ANONYMOUS");
        assertThat(exported.severity()).isEqualTo("WARNING");
    }

    private AuditEntry fullEntry() {
        return new AuditEntry(42L, Instant.now(), "someone@example.com", AuditType.AUTH_FAILURE.name(),
                AuditCategory.AUTHENTICATION, false, "detail", AuditSubjectType.NONE, null,
                AuditActorType.ANONYMOUS, ACTOR_ID, "admin@example.com", "The Admin",
                "203.0.113.7", "curl/8", "cli", "req-1", "auth.failed", AuditSeverity.WARNING);
    }

    private AuditExportRecord fullRecord() {
        return new AuditExportRecord(42L, Instant.now(), AuditType.AUTH_FAILURE.name(), "AUTHENTICATION",
                "someone@example.com", false, "detail", "auth.failed", "WARNING", "ANONYMOUS", ACTOR_ID,
                "admin@example.com", "The Admin", "NONE", null,
                "203.0.113.7", "curl/8", "cli", "req-1", ORG_ID);
    }
}
