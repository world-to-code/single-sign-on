package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.portal.AppStepUp;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.LockedException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FactorStepService}: it enforces policy step-order, the enroll-at-login gate,
 * and account lockout, and on a correct response grants the factor and advances the flow. The grant
 * and the failure/success bookkeeping are the unit's job, so they are asserted with {@code verify(...)}.
 */
@ExtendWith(MockitoExtension.class)
class FactorStepServiceTest {

    @Mock private CurrentUserProvider currentUser;
    @Mock private AuthStateService authState;
    @Mock private FactorHandlers factorHandlers;
    @Mock private AuthPolicyResolver authPolicies;
    @Mock private LoginAttemptService loginAttempts;
    @Mock private FactorAuthorizationService factorAuth;
    @Mock private AuthenticationCompletionService completionService;
    @Mock private AppStepUp appStepUp;
    @Mock private AuditService audit;
    @Mock private PreAuthOrgSession preAuthOrg;
    @Mock private OrgContext orgContext;

    @Mock private UserAccount user;
    @Mock private FactorHandler handler;
    @Mock private AuthPolicyView policy;

    @InjectMocks private FactorStepService service;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    /** The policy currently expects TOTP (next=FACTOR, pending=[TOTP]). */
    private void expectTotpStep() {
        lenient().when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.pending(
                "alice", true, false, List.of(), List.of(), List.of(), List.of("TOTP"), true, null));
    }

    private FactorVerificationRequest code(String value) {
        return new FactorVerificationRequest(value, null, null);
    }

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(user);
        lenient().when(user.getUsername()).thenReturn("alice");
    }

    @Test
    void verifyingAFactorOutOfStepOrderIsRejected() {
        expectTotpStep();

        assertThatThrownBy(() -> service.verify(AuthFactor.EMAIL, code("1"), request, response))
                .isInstanceOf(BadRequestException.class);
        verify(factorHandlers, never()).get(any());
    }

    @Test
    void verifyingWhileLockedIsRejectedAndAudited() {
        expectTotpStep();
        when(user.isTemporarilyLocked(any(Instant.class))).thenReturn(true);

        assertThatThrownBy(() -> service.verify(AuthFactor.TOTP, code("123456"), request, response))
                .isInstanceOf(LockedException.class);
        verify(audit).record(any(AuditRecord.class));
        verify(factorAuth, never()).grantFactor(any(), any(), any());
    }

    @Test
    void anIncorrectCodeCountsAFailureAndThrowsBadRequest() {
        expectTotpStep();
        when(user.isTemporarilyLocked(any(Instant.class))).thenReturn(false);
        when(user.isAccountNonLocked()).thenReturn(true);
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(false);

        assertThatThrownBy(() -> service.verify(AuthFactor.TOTP, code("000000"), request, response))
                .isInstanceOf(BadRequestException.class);
        verify(loginAttempts).onFailure("alice");
        verify(factorAuth, never()).grantFactor(any(), any(), any());
    }

    @Test
    void aCorrectCodeGrantsTheFactorAndCompletesTheFlow() {
        expectTotpStep();
        when(user.isTemporarilyLocked(any(Instant.class))).thenReturn(false);
        when(user.isAccountNonLocked()).thenReturn(true);
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(true);
        when(completionService.completeIfSatisfied(request, response))
                .thenReturn(AuthSessionView.organizationPending(true));

        service.verify(AuthFactor.TOTP, code("123456"), request, response);

        verify(loginAttempts).onSuccess("alice");
        verify(factorAuth).grantFactor(request, response, AuthFactor.TOTP.authority());
        verify(completionService).completeIfSatisfied(request, response);
    }

    @Test
    void preparingAnUnenrolledFactorIsBlockedWhenEnrollAtLoginIsDisabled() {
        expectTotpStep();
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.enrollableAtLogin()).thenReturn(true);
        when(handler.isEnrolled(user)).thenReturn(false);
        when(authPolicies.resolveForUser(user)).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(false);

        assertThatThrownBy(() -> service.prepare(AuthFactor.TOTP, request))
                .isInstanceOf(ForbiddenException.class);
        verify(handler, never()).prepare(any(), any());
    }

    @Test
    void theEnrollAtLoginGateResolvesInTheLoginOrgSoTheTenantPolicyGovernsNotTheGlobalDefault() {
        UUID loginOrg = UUID.randomUUID();
        expectTotpStep();
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(loginOrg));
        when(orgContext.callInOrg(eq(loginOrg), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.enrollableAtLogin()).thenReturn(true);
        when(handler.isEnrolled(user)).thenReturn(false);
        when(authPolicies.resolveForUser(user)).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(false); // the LOGIN org's policy forbids enroll-at-login

        assertThatThrownBy(() -> service.prepare(AuthFactor.TOTP, request))
                .isInstanceOf(ForbiddenException.class);
        // The gate was evaluated bound to the login org — a user cannot dodge a tenant's stricter policy
        // by exploiting the no-context (global-default) resolution window.
        verify(orgContext).callInOrg(eq(loginOrg), any());
        verify(handler, never()).prepare(any(), any());
    }
}
