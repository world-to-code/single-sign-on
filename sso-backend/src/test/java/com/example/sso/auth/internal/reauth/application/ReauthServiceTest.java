package com.example.sso.auth.internal.reauth.application;

import com.example.sso.auth.internal.factor.application.FactorChallenge;
import com.example.sso.auth.internal.factor.application.FactorHandler;
import com.example.sso.auth.internal.factor.application.FactorHandlers;
import com.example.sso.auth.internal.factor.application.FactorVerificationRequest;
import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.session.lifecycle.SessionLifecycle;
import com.example.sso.session.policy.EffectiveSessionPolicy;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.session.lifecycle.StepUpInterceptor;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ReauthService} — the ONLY writer of the deliberate step-up markers
 * ({@code STEPUP_TIME}/{@code STEPUP_FACTOR}) that a sensitive action's strength floor checks. It proves:
 * <ul>
 *   <li>a successful verify stamps the deliberate step-up (with the exact factor) and clears the pending
 *       challenge set, so and only so a subsequent sensitive action can pass its gate;</li>
 *   <li>a wrong factor response neither stamps nor rotates — it audits a failure and 400s;</li>
 *   <li>the pending challenge set is a real gate: a factor outside it is rejected BEFORE the handler runs
 *       (this is where the strength floor is enforced at the re-auth entry), falling back to the policy's
 *       general re-auth factors only when no challenge is pending;</li>
 *   <li>session-id rotation happens on a policy that demands it.</li>
 * </ul>
 * Collaborators are mocked; a real {@link MockHttpSession} lets us assert the stamped attributes.
 */
@ExtendWith(MockitoExtension.class)
class ReauthServiceTest {

    @Mock private CurrentUserProvider currentUser;
    @Mock private UserSessionPolicy sessionPolicy;
    @Mock private FactorHandlers factorHandlers;
    @Mock private SessionLifecycle sessions;
    @Mock private FactorAuthorizationService factorAuth;
    @Mock private AuditService audit;
    @Mock private FactorHandler handler;

    @InjectMocks private ReauthService service;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = mock(UserAccount.class);
        lenient().when(user.getUsername()).thenReturn("alice");
        lenient().when(currentUser.require()).thenReturn(user);
        // The effective policy carries the org-authoritative re-auth factors and the rotate-on-reauth preference
        // directly (no raw winner policy is exposed).
        lenient().when(sessionPolicy.effectiveForUser(user)).thenReturn(effectiveWith("TOTP,FIDO2", false));
    }

    private EffectiveSessionPolicy effectiveWith(String reauthFactors, boolean rotateOnReauth) {
        return new EffectiveSessionPolicy(30, 480, 15, reauthFactors, false, rotateOnReauth);
    }

    /** A request whose session already carries a pending challenge for {@code pendingFactors} (or none if null). */
    private MockHttpServletRequest requestWithPending(String pendingFactors) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/reauth/verify");
        MockHttpSession session = new MockHttpSession();
        if (pendingFactors != null) {
            session.setAttribute(StepUpInterceptor.STEPUP_FACTORS, pendingFactors);
        }
        request.setSession(session);
        return request;
    }

    private FactorVerificationRequest response(String code) {
        return new FactorVerificationRequest(code, null, null);
    }

    @Test
    void verifyStampsTheDeliberateStepUpAndClearsThePendingSetOnSuccess() {
        MockHttpServletRequest request = requestWithPending("FIDO2");
        when(factorHandlers.get(AuthFactor.FIDO2)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(true);

        service.verify(AuthFactor.FIDO2, response("cred"), request, new MockHttpServletResponse());

        MockHttpSession session = (MockHttpSession) request.getSession();
        long now = System.currentTimeMillis();
        assertThat(session.getAttribute(StepUpInterceptor.STEPUP_FACTOR)).isEqualTo("FIDO2");
        // FRESH, not merely a Long: the sensitive gate keys off now - STEPUP_TIME <= window, so a stale
        // stamp would silently make every sensitive action fail (or, if backdated the other way, never expire).
        assertThat(now - (long) session.getAttribute(StepUpInterceptor.STEPUP_TIME)).isBetween(0L, 5_000L);
        assertThat(now - (long) session.getAttribute(StepUpInterceptor.REAUTH_ACTIVITY)).isBetween(0L, 5_000L);
        assertThat(session.getAttribute(StepUpInterceptor.STEPUP_FACTORS)).isNull(); // pending challenge consumed
        verify(sessions, never()).rotateSessionId(any(), any()); // rotation is gated on isRotateOnReauth (false here)
        // The session PRESENTED this factor — it must be granted, so the factor set (and the acr/amr the
        // elevation token derives from it) reflects reality: a password-login session that re-auths with a
        // second factor is genuinely multi-factor. Without this, admin elevation (acr=mfa) loops forever.
        verify(factorAuth).grantFactor(eq(request), any(), eq(AuthFactor.FIDO2.authority()));
        verify(factorAuth).restampAuthTime(eq(request), any());
        verify(audit).record(auditOfType(AuditType.REAUTH_SUCCESS));
    }

    @Test
    void verifyWithAWrongResponseNeitherStampsNorRotatesAndAuditsFailure() {
        MockHttpServletRequest request = requestWithPending("TOTP,FIDO2");
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(false);

        assertThatThrownBy(() -> service.verify(AuthFactor.TOTP, response("000000"), request, new MockHttpServletResponse()))
                .isInstanceOf(BadRequestException.class);

        MockHttpSession session = (MockHttpSession) request.getSession();
        assertThat(session.getAttribute(StepUpInterceptor.STEPUP_TIME)).isNull();
        assertThat(session.getAttribute(StepUpInterceptor.STEPUP_FACTOR)).isNull();
        assertThat(session.getAttribute(StepUpInterceptor.STEPUP_FACTORS)).isEqualTo("TOTP,FIDO2"); // still pending
        verify(sessions, never()).rotateSessionId(any(), any());
        verify(factorAuth, never()).grantFactor(any(), any(), any()); // a failed factor must never be granted
        verify(factorAuth, never()).restampAuthTime(any(), any());
        verify(audit).record(auditOfType(AuditType.REAUTH_FAILURE));
    }

    @Test
    void verifyRejectsAFactorOutsideThePendingChallengeSetBeforeRunningTheHandler() {
        // The strength floor at the re-auth entry: a FIDO2-only pending challenge cannot be answered with TOTP.
        MockHttpServletRequest request = requestWithPending("FIDO2");

        assertThatThrownBy(() -> service.verify(AuthFactor.TOTP, response("000000"), request, new MockHttpServletResponse()))
                .isInstanceOf(BadRequestException.class)
                // The detail is now localized via a MessageSource; the exception message carries the key.
                .hasMessageContaining("auth.reauth.factorNotAllowed");

        verify(factorHandlers, never()).get(any()); // rejected before any handler dispatch
        assertThat(((MockHttpSession) request.getSession()).getAttribute(StepUpInterceptor.STEPUP_TIME)).isNull();
    }

    @Test
    void verifyFallsBackToThePolicyReauthFactorsWhenNoChallengeIsPending() {
        // A proactive re-auth with no interceptor challenge: allowed factors come from the policy.
        MockHttpServletRequest request = requestWithPending(null);
        when(factorHandlers.get(AuthFactor.TOTP)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(true);

        service.verify(AuthFactor.TOTP, response("123456"), request, new MockHttpServletResponse());

        assertThat(((MockHttpSession) request.getSession()).getAttribute(StepUpInterceptor.STEPUP_FACTOR)).isEqualTo("TOTP");
    }

    @Test
    void verifyRejectsAFactorOutsideThePolicyReauthFactorsWhenNoChallengeIsPending() {
        when(sessionPolicy.effectiveForUser(user)).thenReturn(effectiveWith("FIDO2", false));
        MockHttpServletRequest request = requestWithPending(null);

        assertThatThrownBy(() -> service.verify(AuthFactor.TOTP, response("123456"), request, new MockHttpServletResponse()))
                .isInstanceOf(BadRequestException.class);
        verify(factorHandlers, never()).get(any());
    }

    @Test
    void verifyRotatesTheSessionIdWhenThePolicyDemandsIt() {
        when(sessionPolicy.effectiveForUser(user)).thenReturn(effectiveWith("TOTP,FIDO2", true));
        MockHttpServletRequest request = requestWithPending("FIDO2");
        when(factorHandlers.get(AuthFactor.FIDO2)).thenReturn(handler);
        when(handler.verify(eq(user), any(), eq(request))).thenReturn(true);

        service.verify(AuthFactor.FIDO2, response("cred"), request, new MockHttpServletResponse());

        // Rotation must precede the post-stamp work (auth-time re-stamp), so the id-rotated session is the
        // one carried forward and the SessionRegistry stays consistent.
        InOrder inOrder = inOrder(sessions, factorAuth);
        inOrder.verify(sessions).rotateSessionId(request, "alice");
        inOrder.verify(factorAuth).restampAuthTime(eq(request), any());
        assertThat(((MockHttpSession) request.getSession()).getAttribute(StepUpInterceptor.STEPUP_FACTOR)).isEqualTo("FIDO2");
    }

    @Test
    void prepareRejectsAFactorOutsideThePendingChallengeSet() {
        MockHttpServletRequest request = requestWithPending("FIDO2");

        assertThatThrownBy(() -> service.prepare(AuthFactor.TOTP, request))
                .isInstanceOf(BadRequestException.class);
        verify(factorHandlers, never()).get(any());
    }

    @Test
    void prepareReturnsTheChallengeForAnAllowedFactor() {
        MockHttpServletRequest request = requestWithPending("FIDO2");
        FactorChallenge challenge = new FactorChallenge(true, null, null, "{options}");
        when(factorHandlers.get(AuthFactor.FIDO2)).thenReturn(handler);
        when(handler.prepare(user, request)).thenReturn(challenge);

        assertThat(service.prepare(AuthFactor.FIDO2, request)).isSameAs(challenge);
    }

    private AuditRecord auditOfType(AuditType type) {
        return argThat(r -> r != null && r.type() == type);
    }
}
