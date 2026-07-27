package com.example.sso.admin.internal.deny.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deny admin orchestration: it delegates authorization, the last-admin guard, the write and session
 * termination to {@link DenyService} and audits only a change that actually happened.
 */
@ExtendWith(MockitoExtension.class)
class DenyAdminServiceTest {

    @Mock private DenyService denyService;
    @Mock private AdminAuditLogger auditLogger;

    @InjectMocks private DenyAdminService service;

    @Test
    void createDelegatesToTheDenyServiceAndAudits() {
        UUID denyId = UUID.randomUUID();
        DenySpec spec = new DenySpec(DenySubjectKind.USER, UUID.randomUUID(), "user:read");
        when(denyService.create(spec)).thenReturn(denyId);

        assertThat(service.create(spec)).isEqualTo(denyId);
        verify(denyService).create(spec);
        verify(auditLogger).log(eq(AuditType.PERMISSION_DENY_CREATED), any());
    }

    @Test
    void liftDelegatesToTheDenyServiceAndAudits() {
        UUID denyId = UUID.randomUUID();

        service.lift(denyId, DenySubjectKind.ROLE);

        verify(denyService).lift(denyId, DenySubjectKind.ROLE);
        verify(auditLogger).log(eq(AuditType.PERMISSION_DENY_LIFTED), any());
    }

    @Test
    void aSuccessfulCreateIsNotAuditedAsAFailure() {
        DenySpec spec = new DenySpec(DenySubjectKind.USER, UUID.randomUUID(), "user:read");
        when(denyService.create(spec)).thenReturn(UUID.randomUUID());

        service.create(spec);

        verify(auditLogger, never()).logFailure(any(), any(), any());
    }

    @Test
    void aRefusedCreateIsAuditedAsAFailureAndRethrown() {
        // The per-subject refusal is the AUTHORITATIVE one (the coarse URL check already passed), so without this
        // line a probe for which subjects an actor may withhold permissions from leaves no trail at all.
        DenySpec spec = new DenySpec(DenySubjectKind.USER, UUID.randomUUID(), "user:read");
        when(denyService.create(spec)).thenThrow(ForbiddenException.of("user.deny.notPermitted"));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(ForbiddenException.class);
        verify(auditLogger).logFailure(eq(AuditType.PERMISSION_DENY_CREATED), any(), eq("user.deny.notPermitted"));
        verify(auditLogger, never()).log(any(), any()); // never recorded as a change that happened
    }

    @Test
    void aBrickingCreateIsAuditedAsAFailureAndRethrown() {
        DenySpec spec = new DenySpec(DenySubjectKind.ROLE, UUID.randomUUID(), "user:update");
        when(denyService.create(spec)).thenThrow(ConflictException.of("admin.lastAdmin"));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(ConflictException.class);
        verify(auditLogger).logFailure(eq(AuditType.PERMISSION_DENY_CREATED), any(), eq("admin.lastAdmin"));
        verify(auditLogger, never()).log(any(), any());
    }

    @Test
    void aCreateThatFailsForANonRefusalReasonIsNotAuditedAsARefusal() {
        // Widening the catch would record an invalid-pattern 400 (whose message carries the caller's own text)
        // and every incidental failure as an authorization refusal, polluting the security trail.
        DenySpec spec = new DenySpec(DenySubjectKind.USER, UUID.randomUUID(), "bogus");
        when(denyService.create(spec)).thenThrow(BadRequestException.of("user.permission.unknown", "bogus"));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(BadRequestException.class);
        verify(auditLogger, never()).logFailure(any(), any(), any());
    }

    @Test
    void aFailingAuditNeverReplacesTheRefusalItRecords() {
        // logFailure borrows a second connection for its own transaction; if that fails, the caller must still
        // see the 409 rather than a 500 — the trail is the addition, never the outcome.
        DenySpec spec = new DenySpec(DenySubjectKind.ROLE, UUID.randomUUID(), "user:update");
        when(denyService.create(spec)).thenThrow(ConflictException.of("admin.lastAdmin"));
        doThrow(new IllegalStateException("pool exhausted"))
                .when(auditLogger).logFailure(any(), any(), any());

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aSuccessfulLiftIsNotAuditedAsAFailure() {
        service.lift(UUID.randomUUID(), DenySubjectKind.USER);

        verify(auditLogger, never()).logFailure(any(), any(), any());
    }

    @Test
    void aRefusedLiftIsAuditedAsAFailureAndRethrown() {
        // Lifting is the RESTORING direction, so a refused lift is the attempt to widen someone's authority —
        // equally worth a trail, and the dominance guard is what refused it.
        UUID denyId = UUID.randomUUID();
        doThrow(ForbiddenException.of("user.deny.notPermitted"))
                .when(denyService).lift(denyId, DenySubjectKind.ROLE);

        assertThatThrownBy(() -> service.lift(denyId, DenySubjectKind.ROLE))
                .isInstanceOf(ForbiddenException.class);
        verify(auditLogger).logFailure(eq(AuditType.PERMISSION_DENY_LIFTED), any(), eq("user.deny.notPermitted"));
        verify(auditLogger, never()).log(any(), any());
    }
}
