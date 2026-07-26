package com.example.sso.admin.internal.deny.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditType;
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
    void aRefusedOrBrickingCreateThrowsBeforeAuditing() {
        DenySpec spec = new DenySpec(DenySubjectKind.USER, UUID.randomUUID(), "user:read");
        when(denyService.create(spec)).thenThrow(ForbiddenException.of("user.deny.notPermitted"));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(ForbiddenException.class);
        verify(auditLogger, never()).log(any(), any());
    }
}
