package com.example.sso.auth.internal.factor.application;

import com.example.sso.auth.internal.login.application.AuthSessionView;
import com.example.sso.auth.internal.login.application.AuthStateService;
import com.example.sso.auth.internal.login.application.AuthenticationCompletionService;
import com.example.sso.auth.internal.login.application.CurrentUserProvider;
import com.example.sso.auth.internal.login.application.LoginAttemptService;
import com.example.sso.auth.internal.login.application.LoginPolicyResolver;
import com.example.sso.auth.internal.login.application.PreAuthOrgSession;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.stepup.AppStepUp;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.LockedException;
import com.example.sso.user.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Login-time factor stepping: issues a factor's pre-step data and verifies the user's response,
 * enforcing policy step-order, the enroll-at-login gate, account lockout, and app step-up freshness.
 * Grants the factor and advances the policy on success.
 */
@Service
@RequiredArgsConstructor
public class FactorStepService {

    private final CurrentUserProvider currentUser;
    private final AuthStateService authState;
    private final FactorHandlers factorHandlers;
    private final LoginPolicyResolver loginPolicy;
    private final LoginAttemptService loginAttempts;
    private final FactorAuthorizationService factorAuth;
    private final AuthenticationCompletionService completionService;
    private final AppStepUp appStepUp;
    private final AuditService audit;
    private final PreAuthOrgSession preAuthOrg;
    private final OrganizationService organizations;

    /** Issues any pre-step data for the factor (TOTP QR, WebAuthn options, or sends an email code). */
    public FactorChallenge prepare(AuthFactor factor, HttpServletRequest request) {
        UserAccount user = currentUser.require();
        UUID loginOrgId = preAuthOrg.orgId(request).orElse(null);
        requireCurrentStep(factor, loginOrgId); // can only act on the factor the policy currently expects

        // Keycloak-style gate: setting up an un-enrolled factor (which factors are enrollable is owned
        // by the strategy) during login is only allowed when the winning login policy permits enroll-at-login
        // — resolved in the login org so the tenant's own policy (not just the global default) governs.
        FactorHandler handler = factorHandlers.get(factor);
        if (handler.enrollableAtLogin() && !handler.isEnrolled(user)
                && !resolveForLogin(user, loginOrgId).isAllowEnrollmentAtLogin()) {
            throw new ForbiddenException(
                    "Setting up a new authenticator during login is disabled. Contact your administrator.");
        }

        return handler.prepare(user, request);
    }

    private AuthPolicyView resolveForLogin(UserAccount user, UUID loginOrgId) {
        return loginPolicy.resolve(user, loginOrgId);
    }

    /** Verifies the user's response; on success grants the factor and returns the (possibly completed) view. */
    public AuthSessionView verify(AuthFactor factor, FactorVerificationRequest verification,
                                  HttpServletRequest request, HttpServletResponse response) {
        UserAccount user = currentUser.require();
        UUID loginOrgId = preAuthOrg.orgId(request).orElse(null);
        // reject factors out of policy order (e.g. TOTP before password), per the login org's own policy
        requireCurrentStep(factor, loginOrgId);
        // A passkey as the FIRST factor is passwordless login — gate it on the org's opt-in, symmetric with
        // /login/webauthn, so the admin toggle is a real kill-switch even when the policy allows FIDO2 first.
        requirePasswordlessAllowedForPasskeyFirst(factor, loginOrgId);

        // Account lockout applies to every factor (password is verified here too, not just /login).
        if (user.isTemporarilyLocked(Instant.now()) || !user.isAccountNonLocked()) {
            audit.record(mfaRecord(AuditType.MFA_LOCKED, user.getUsername(), factor, loginOrgId));
            throw LockedException.of("auth.account.locked");
        }

        if (factorHandlers.get(factor).verify(user, verification, request)) {
            loginAttempts.onSuccess(user.getUsername());
            factorAuth.grantFactor(request, response, factor.authority());
            appStepUp.stampIfPending(request.getSession(false)); // refresh app step-up freshness if a launch is pending
            audit.record(new AuditRecord(AuditType.MFA_SUCCESS, user.getUsername(), true, "factor=" + factor.name(),
                    null, loginOrgId));
            return completionService.completeIfSatisfied(request, response);
        }

        loginAttempts.onFailure(user.getUsername());
        audit.record(mfaRecord(AuditType.MFA_FAILURE, user.getUsername(), factor, loginOrgId));
        throw BadRequestException.of("auth.code.incorrect");
    }

    /**
     * When FIDO2 is verified as the FIRST factor (none granted yet), that is passwordless passkey sign-in —
     * allowed only if the login org opted in. A FIDO2 SECOND factor (step-up, after another factor) is
     * unaffected. Mirrors the {@code /login/webauthn} enforcement so the toggle governs every passkey-first path.
     */
    private void requirePasswordlessAllowedForPasskeyFirst(AuthFactor factor, UUID loginOrgId) {
        if (factor != AuthFactor.FIDO2 || !noFactorProven()) {
            return; // not a passkey, or not the first factor (a later step-up) — passwordless gate doesn't apply
        }
        if (!organizations.isPasswordlessLoginEnabled(loginOrgId)) {
            throw ForbiddenException.of("auth.passwordless.disabled");
        }
    }

    /**
     * A factor-step failure/lockout audit record, tagged to the login org and marked UNVERIFIED when no factor
     * has been proven yet (passwordless / identify-first). At that point the principal is only a caller-supplied
     * name, so it must not be resolved to a real account (no id/email enrichment, no enumeration/framing). Once a
     * factor is proven (password-first, or a second factor), the principal is verified and enriches normally.
     */
    private AuditRecord mfaRecord(AuditType type, String username, AuthFactor factor, UUID loginOrgId) {
        AuditRecord record = new AuditRecord(type, username, false, "factor=" + factor.name(), null, loginOrgId);
        return noFactorProven() ? record.unverifiedActor() : record;
    }

    /** Whether the pre-auth principal has proven NO factor yet — its identity is therefore unproven. */
    private boolean noFactorProven() {
        return currentUser.authentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).noneMatch(a -> a.startsWith(Factors.FACTOR_PREFIX));
    }

    /**
     * During the initial (pre-MFA_COMPLETE) login, rejects acting on a factor that is not the policy's
     * current step — preventing step-skipping or planting a factor before authentication. Once fully
     * authenticated, the /factors endpoints are reused for per-app step-up, where login step-ordering no
     * longer applies — so allow it.
     */
    private void requireCurrentStep(AuthFactor factor, UUID loginOrgId) {
        // only next() is read; resolved in the login org so step-ordering follows the tenant's own policy
        AuthSessionView view = authState.describe(currentUser.authentication(), null, loginOrgId);
        if (AuthSessionView.NEXT_DONE.equals(view.next())) {
            return; // fully authenticated -> step-up context, not initial login ordering
        }

        if (!view.pendingFactors().contains(factor.name())) {
            throw BadRequestException.of("auth.step.unexpected");
        }
    }
}
