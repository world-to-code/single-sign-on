package com.example.sso.audit.internal.domain;

import com.example.sso.audit.AuditActorType;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code AuditEvent.of} factory wires the raw record plus the resolved enrichment into the persisted
 * columns. This pins each mapping so a swap or drop among the many same-typed String columns (actor
 * email/display, user-agent, device, request id, reason) cannot slip through — and pins the remote-IP
 * fallback (the caller's explicit IP wins; the captured client IP fills in when the caller had none).
 */
class AuditEventFactoryTest {

    private final UUID actorId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @Test
    void mapsEveryActorClientAndOutcomeFieldToItsOwnColumn() {
        AuditRecord record = new AuditRecord(AuditType.USER_UPDATED, "alice", true, "PUT /users/u1", "203.0.113.9",
                AuditSubjectType.USER, "u1", orgId).withReason("profile changed");
        AuditActorInfo actor = AuditActorInfo.user(actorId, "alice@acme.test", "Alice A", "alice");
        AuditClientInfo client = new AuditClientInfo("10.0.0.1", "Chrome UA", "Chrome on macOS", "trace-abc");

        AuditEvent event = AuditEvent.of(record, actor, client, AuditSeverity.WARNING, orgId);

        assertThat(event.getType()).isEqualTo(AuditType.USER_UPDATED.name());
        assertThat(event.getPrincipal()).isEqualTo("alice");
        assertThat(event.getSubjectType()).isEqualTo(AuditSubjectType.USER);
        assertThat(event.getSubjectId()).isEqualTo("u1");
        assertThat(event.getActorType()).isEqualTo(AuditActorType.USER);
        assertThat(event.getActorId()).isEqualTo(actorId);
        assertThat(event.getActorEmail()).isEqualTo("alice@acme.test");
        assertThat(event.getActorDisplay()).isEqualTo("Alice A");
        assertThat(event.getUserAgent()).isEqualTo("Chrome UA");
        assertThat(event.getDevice()).isEqualTo("Chrome on macOS");
        assertThat(event.getRequestId()).isEqualTo("trace-abc");
        assertThat(event.getReason()).isEqualTo("profile changed");
        assertThat(event.getSeverity()).isEqualTo(AuditSeverity.WARNING);
        assertThat(event.getOrgId()).isEqualTo(orgId);
        assertThat(event.getRemoteIp()).isEqualTo("203.0.113.9"); // the caller's explicit IP wins
    }

    @Test
    void fallsBackToTheCapturedClientIpWhenTheRecordHasNone() {
        AuditRecord record = new AuditRecord(AuditType.AUTH_SUCCESS, "bob", true, "login", null);
        AuditClientInfo client = new AuditClientInfo("198.51.100.7", "UA", "device", "trace");

        AuditEvent event = AuditEvent.of(record, AuditActorInfo.of(AuditActorType.ANONYMOUS, "bob"),
                client, AuditSeverity.INFO, orgId);

        assertThat(event.getRemoteIp()).isEqualTo("198.51.100.7"); // captured client IP fills the gap
    }

    @Test
    void aNoneClientLeavesTheClientColumnsNull() {
        AuditRecord record = new AuditRecord(AuditType.AUTH_SUCCESS, "bob", true, "login", null);

        AuditEvent event = AuditEvent.of(record, AuditActorInfo.of(AuditActorType.SYSTEM, "system:x"),
                AuditClientInfo.NONE, AuditSeverity.INFO, orgId);

        assertThat(event.getRemoteIp()).isNull();
        assertThat(event.getUserAgent()).isNull();
        assertThat(event.getDevice()).isNull();
        assertThat(event.getRequestId()).isNull();
    }
}
