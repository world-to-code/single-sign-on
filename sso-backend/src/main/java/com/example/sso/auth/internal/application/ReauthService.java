package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.session.SessionLifecycle;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.StepUpInterceptor;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
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
    private final SessionPolicyService sessionPolicy;
    private final FactorHandlers factorHandlers;
    private final SessionLifecycle sessions;
    private final FactorAuthorizationService factorAuth;
    private final AuditService audit;

    /** Pre-step data for a re-auth factor (e.g. WebAuthn options); must be a policy-allowed re-auth factor. */
    public FactorChallenge prepare(AuthFactor factor, HttpServletRequest request) {
        UserAccount user = currentUser.require();
        requireReauthFactor(sessionPolicy.resolveForUser(user), factor);
        return factorHandlers.get(factor).prepare(user, request);
    }

    /** Verifies a fresh factor and refreshes the step-up clock; incorrect response → 400. */
    public void verify(AuthFactor factor, FactorVerificationRequest verification,
                       HttpServletRequest request, HttpServletResponse response) {
        UserAccount user = currentUser.require();
        SessionPolicyDetails policy = sessionPolicy.resolveForUser(user);
        requireReauthFactor(policy, factor);

        if (!factorHandlers.get(factor).verify(user, verification, request)) {
            audit.record(new AuditRecord(AuditType.REAUTH_FAILURE, user.getUsername(), false, "factor=" + factor, null));
            throw new BadRequestException("Re-authentication failed.");
        }

        // Per-policy defence in depth: rotate the session id on a successful re-auth BEFORE the response
        // (and the step-up stamp) are written, keeping the SessionRegistry consistent.
        if (policy.isRotateOnReauth()) {
            sessions.rotateSessionId(request, user.getUsername());
        }

        StepUpInterceptor.stamp(request.getSession(true));
        // Re-stamp the session Authentication's auth-time marker so an admin elevation token minted from
        // the OIDC flow right after this step-up carries a FRESH auth_time (RFC 9470 step-up).
        factorAuth.restampAuthTime(request, response);
        audit.record(new AuditRecord(AuditType.REAUTH_SUCCESS, user.getUsername(), true, "factor=" + factor, null));
    }

    private void requireReauthFactor(SessionPolicyDetails policy, AuthFactor factor) {
        boolean allowed = Arrays.stream(policy.getReauthFactors().split(","))
                .map(String::trim).anyMatch(f -> f.equals(factor.name()));
        if (!allowed) {
            throw new BadRequestException(factor + " is not an allowed re-auth factor");
        }
    }
}
