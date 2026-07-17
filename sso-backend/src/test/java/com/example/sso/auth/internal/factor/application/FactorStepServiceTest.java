package com.example.sso.auth.internal.factor.application;

import com.example.sso.auth.internal.login.application.AuthSessionView;
import com.example.sso.auth.internal.login.application.AuthStateService;
import com.example.sso.auth.internal.login.application.AuthenticationCompletionService;
import com.example.sso.auth.internal.login.application.CurrentUserProvider;
import com.example.sso.auth.internal.login.application.LoginAttemptService;
import com.example.sso.auth.internal.login.application.LoginPolicyResolver;
import com.example.sso.auth.internal.login.application.PreAuthOrgSession;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.stepup.AppStepUp;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.LockedException;
import com.example.sso.user.account.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private LoginPolicyResolver loginPolicy;
    @Mock private LoginAttemptService loginAttempts;
    @Mock private FactorAuthorizationService factorAuth;
    @Mock private AuthenticationCompletionService completionService;
    @Mock private AppStepUp appStepUp;
    @Mock private AuditService audit;
    @Mock private PreAuthOrgSession preAuthOrg;
    @Mock private OrganizationService organizations;

    @Mock private UserAccount user;
    @Mock private FactorHandler handler;
    @Mock private AuthPolicyView policy;

    @InjectMocks private FactorStepService service;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    /** The policy currently expects TOTP (next=FACTOR, pending=[TOTP]). */
    private void expectTotpStep() {
        lenient().when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.pending(
                "alice", true, false, List.of(), List.of(), List.of(), List.of("TOTP"), true, null, false));
    }

    private FactorVerificationRequest code(String value) {
        return new FactorVerificationRequest(value, null, null);
    }

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(user);
        lenient().when(user.getUsername()).thenReturn("alice");
        // No factor proven yet (empty authorities) — the default pre-auth state; factor-step failures are then
        // recorded with an unverified actor. Individual tests override this when they need a proven factor.
        lenient().when(currentUser.authentication())
                .thenReturn(new UsernamePasswordAuthenticationToken("alice", null));
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
    void aFirstFactorFailureRecordsAnUnverifiedActor() {
        // No factor proven yet (setUp default) — a passwordless/identify-first failure. The principal is unproven,
        // so the audit record must be unverified (no account resolution, no enumeration/PII of the guessed name).
        expectTotpStep();
        when(user.isTemporarilyLocked(any(Instant.class))).thenReturn(false);
        when(user.isAccountNonLocked()).thenReturn(true);
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(false);

        assertThatThrownBy(() -> service.verify(AuthFactor.TOTP, code("000000"), request, response))
                .isInstanceOf(BadRequestException.class);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(AuditType.MFA_FAILURE);
        assertThat(captor.getValue().verifiedActor()).isFalse();
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
        when(loginPolicy.resolve(eq(user), any())).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(false);

        assertThatThrownBy(() -> service.prepare(AuthFactor.TOTP, request))
                .isInstanceOf(ForbiddenException.class);
        verify(handler, never()).prepare(any(), any());
    }

    @Test
    void theEnrollAtLoginGateResolvesTheLoginPolicyForTheBoundLoginOrg() {
        // The org-scoped, tenant-vs-global resolution itself lives in LoginPolicyResolver (tested there); here
        // we assert the gate threads the LOGIN org so a user cannot dodge a tenant's stricter policy through
        // the no-context (global-default) window.
        UUID loginOrg = UUID.randomUUID();
        expectTotpStep();
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(loginOrg));
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.enrollableAtLogin()).thenReturn(true);
        when(handler.isEnrolled(user)).thenReturn(false);
        when(loginPolicy.resolve(user, loginOrg)).thenReturn(policy);
        when(policy.isAllowEnrollmentAtLogin()).thenReturn(false); // the LOGIN org's policy forbids enroll-at-login

        assertThatThrownBy(() -> service.prepare(AuthFactor.TOTP, request))
                .isInstanceOf(ForbiddenException.class);
        verify(loginPolicy).resolve(user, loginOrg);
        verify(handler, never()).prepare(any(), any());
    }

    @Test
    void verifyRejectsAPasskeyAsTheFirstFactorWhenTheOrgDisablesPasswordless() {
        UUID loginOrg = UUID.randomUUID();
        // The policy currently expects FIDO2, and no factor has been granted yet (passkey-FIRST).
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.pending(
                "alice", false, true, List.of(), List.of(), List.of(), List.of("FIDO2"), true, null, true));
        when(currentUser.authentication())
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(loginOrg));
        when(organizations.isPasswordlessLoginEnabled(loginOrg)).thenReturn(false);

        assertThatThrownBy(() -> service.verify(AuthFactor.FIDO2, code(null), request, response))
                .isInstanceOf(ForbiddenException.class);
        verify(factorAuth, never()).grantFactor(any(), any(), any());
    }

    @Test
    void verifyAllowsAPasskeyAsTheFirstFactorWhenTheOrgEnablesPasswordless() {
        UUID loginOrg = UUID.randomUUID();
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.pending(
                "alice", false, true, List.of(), List.of(), List.of(), List.of("FIDO2"), true, null, true));
        when(currentUser.authentication())
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        when(preAuthOrg.orgId(request)).thenReturn(Optional.of(loginOrg));
        when(organizations.isPasswordlessLoginEnabled(loginOrg)).thenReturn(true);
        when(user.isTemporarilyLocked(any())).thenReturn(false);
        when(user.isAccountNonLocked()).thenReturn(true);
        when(factorHandlers.get(AuthFactor.FIDO2)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(true);
        when(completionService.completeIfSatisfied(request, response))
                .thenReturn(AuthSessionView.organizationPending(true));

        service.verify(AuthFactor.FIDO2, code(null), request, response);

        verify(factorAuth).grantFactor(request, response, AuthFactor.FIDO2.authority());
    }

    @Test
    void verifyAllowsFido2AsASecondFactorEvenWhenPasswordlessIsDisabled() {
        // FIDO2 AFTER another factor is step-up MFA, not passwordless-first — the org toggle must not block it.
        when(authState.describe(any(), any(), any())).thenReturn(AuthSessionView.pending(
                "alice", false, true, List.of(), List.of(), List.of(), List.of("FIDO2"), true, null, false));
        when(currentUser.authentication()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                "alice", null, List.of(new SimpleGrantedAuthority(Factors.PASSWORD)))); // a factor already granted
        when(user.isTemporarilyLocked(any())).thenReturn(false);
        when(user.isAccountNonLocked()).thenReturn(true);
        when(factorHandlers.get(AuthFactor.FIDO2)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(true);
        when(completionService.completeIfSatisfied(request, response))
                .thenReturn(AuthSessionView.organizationPending(true));

        service.verify(AuthFactor.FIDO2, code(null), request, response);

        verify(factorAuth).grantFactor(request, response, AuthFactor.FIDO2.authority());
        verify(organizations, never()).isPasswordlessLoginEnabled(any()); // the gate returns early for a 2nd factor
    }
}
