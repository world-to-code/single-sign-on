package com.example.sso.auth.internal.reauth.application;

import com.example.sso.auth.internal.factor.application.FactorChallenge;
import com.example.sso.auth.internal.factor.application.FactorHandlers;
import com.example.sso.auth.internal.factor.application.FactorVerificationRequest;
import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.session.lifecycle.SessionLifecycle;
import com.example.sso.session.policy.EffectiveSessionPolicy;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.session.lifecycle.StepUpInterceptor;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Step-up re-authentication for sensitive operations: verifies a fresh, policy-allowed factor and
 * refreshes the deliberate-step-up clock (rotating the session id first when the policy demands it),
 * so a subsequent sensitive action or admin elevation carries a fresh {@code auth_time}.
 */
@Service
@RequiredArgsConstructor
public class ReauthService {

    private final CurrentUserProvider currentUser;
    private final UserSessionPolicy sessionPolicy;
    private final FactorHandlers factorHandlers;
    private final SessionLifecycle sessions;
    private final FactorAuthorizationService factorAuth;
    private final AuditService audit;

    /** How long a pending step-up challenge stays honoured; past it an abandoned one is ignored as stale. */
    @Value("${sso.zerotrust.stepup-challenge-validity-minutes}")
    private long challengeValidityMinutes;

    /** Pre-step data for a re-auth factor (e.g. WebAuthn options); must be allowed for the pending step-up. */
    public FactorChallenge prepare(AuthFactor factor, HttpServletRequest request) {
        UserAccount user = currentUser.require();
        requireAllowedFactor(request, sessionPolicy.effectiveForUser(user), factor);
        return factorHandlers.get(factor).prepare(user, request);
    }

    /** Verifies a fresh factor and refreshes the step-up clock; incorrect response → 400. */
    public void verify(AuthFactor factor, FactorVerificationRequest verification,
                       HttpServletRequest request, HttpServletResponse response) {
        UserAccount user = currentUser.require();
        EffectiveSessionPolicy effective = sessionPolicy.effectiveForUser(user);
        requireAllowedFactor(request, effective, factor);

        if (!factorHandlers.get(factor).verify(user, verification, request)) {
            audit.record(new AuditRecord(AuditType.REAUTH_FAILURE, user.getUsername(), false, "factor=" + factor, null));
            throw BadRequestException.of("auth.reauth.failed");
        }

        // Per-policy defence in depth: rotate the session id on a successful re-auth BEFORE the response
        // (and the step-up stamp) are written, keeping the SessionRegistry consistent.
        if (effective.rotateOnReauth()) {
            sessions.rotateSessionId(request, user.getUsername());
        }

        StepUpInterceptor.stampStepUp(request.getSession(true), factor.name()); // records WHICH factor stepped up
        clearPendingStepUp(request.getSession()); // pending step-up satisfied
        // The session has now PRESENTED this factor — grant it, so the factor set (and the acr/amr an
        // elevation token derives from it) reflects reality: a password-login session that re-auths with a
        // second factor is genuinely multi-factor. The admin elevation gate (acr=mfa) depends on this.
        factorAuth.grantFactor(request, response, factor.authority());
        // Re-stamp the session Authentication's auth-time marker so an admin elevation token minted from
        // the OIDC flow right after this step-up carries a FRESH auth_time (RFC 9470 step-up).
        factorAuth.restampAuthTime(request, response);
        audit.record(new AuditRecord(AuditType.REAUTH_SUCCESS, user.getUsername(), true, "factor=" + factor, null));
    }

    /**
     * The factor must be in the set the pending step-up demands: the interceptor records the exact allowed
     * factors on the session when it challenges (a sensitive action's may be stronger than the general
     * re-auth factors). A proactive re-auth with no pending challenge — OR one whose challenge is STALE (an
     * abandoned sensitive action left the attribute behind) — falls back to the effective re-auth factors (the
     * specificity winner's), so a leftover strong-factor set can't wrongly reject a legitimate proactive re-auth.
     * The genuine strength floor is re-enforced by {@code StepUpInterceptor} on the actual sensitive request.
     */
    private void requireAllowedFactor(HttpServletRequest request, EffectiveSessionPolicy effective, AuthFactor factor) {
        String allowed = freshPendingFactors(request.getSession(false)).orElseGet(effective::reauthFactors);
        boolean ok = Arrays.stream(allowed.split(","))
                .map(String::trim).anyMatch(f -> f.equals(factor.name()));
        if (!ok) {
            throw BadRequestException.of("auth.reauth.factorNotAllowed", factor);
        }
    }

    /** The pending step-up's factors if its challenge is still fresh; a stale/absent one is cleared and ignored. */
    private Optional<String> freshPendingFactors(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }
        Object factors = session.getAttribute(StepUpInterceptor.STEPUP_FACTORS);
        Object stampedAt = session.getAttribute(StepUpInterceptor.STEPUP_FACTORS_TIME);
        boolean fresh = stampedAt instanceof Long at
                && System.currentTimeMillis() - at <= challengeValidityMinutes * 60_000L;
        if (factors instanceof String csv && !csv.isBlank() && fresh) {
            return Optional.of(csv);
        }
        clearPendingStepUp(session); // stale (abandoned challenge) or malformed — drop it
        return Optional.empty();
    }

    private void clearPendingStepUp(HttpSession session) {
        if (session != null) {
            session.removeAttribute(StepUpInterceptor.STEPUP_FACTORS);
            session.removeAttribute(StepUpInterceptor.STEPUP_FACTORS_TIME);
        }
    }
}
