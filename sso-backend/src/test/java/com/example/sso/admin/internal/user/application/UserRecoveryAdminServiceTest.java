package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recovery: clearing an obstacle for a locked-out person, which is not the same as granting them anything.
 *
 * <p>Both operations exist to be holdable by a support-shaped role, so the property worth pinning is that
 * neither confers authority — resetting MFA makes the user enrol again, and re-sending the ownership mail only
 * lets them flip a verified flag. And both check the user exists BEFORE acting, or a recovery aimed at a
 * mistyped id succeeds silently and the administrator believes they have unblocked somebody.
 */
@ExtendWith(MockitoExtension.class)
class UserRecoveryAdminServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock private UserService userService;
    @Mock private MfaService mfaService;
    @Mock private AdminAuditLogger auditLogger;

    private UserRecoveryAdminService service;

    @BeforeEach
    void setUp() {
        service = new UserRecoveryAdminService(userService, mfaService, auditLogger);
    }

    private void userExists() {
        when(userService.findById(USER)).thenReturn(Optional.of(mock(UserAccount.class)));
    }

    @Test
    void resettingMfaClearsTheEnrolmentAndLeavesATrail() {
        userExists();

        service.resetUserMfa(USER);

        verify(mfaService).resetMfa(USER);
        verify(auditLogger).log(eq(AuditType.USER_MFA_RESET), eq(AuditSubjectType.USER), any(), any());
    }

    /** A recovery on an id that does not exist must be a 404, not a silent success. */
    @Test
    void resettingMfaForAnUnknownUserDoesNothing() {
        when(userService.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetUserMfa(USER)).isInstanceOf(NotFoundException.class);

        verify(mfaService, never()).resetMfa(any());
        verify(auditLogger, never()).log(any(), any(), any(), any());
    }

    @Test
    void resendingVerificationAsksForTheProofAndLeavesATrail() {
        userExists();

        service.resendEmailVerification(USER);

        verify(userService).requestEmailVerification(USER);
        verify(auditLogger).log(eq(AuditType.USER_UPDATED), eq(AuditSubjectType.USER), any(), any());
    }

    @Test
    void resendingVerificationForAnUnknownUserDoesNothing() {
        when(userService.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resendEmailVerification(USER)).isInstanceOf(NotFoundException.class);

        verify(userService, never()).requestEmailVerification(any());
        verify(auditLogger, never()).log(any(), any(), any(), any());
    }

    /**
     * Recovery grants nothing. If either operation ever starts touching roles, permissions or a password, the
     * collaborator list is where that shows up first — this service is deliberately unable to reach them.
     */
    @Test
    void recoveryNeverTouchesWhatTheUserMayDo() {
        userExists();

        service.resetUserMfa(USER);
        service.resendEmailVerification(USER);

        verify(userService, never()).setDirectPermissions(any(), any());
        verify(userService, never()).setPassword(any(), any());
    }
}
