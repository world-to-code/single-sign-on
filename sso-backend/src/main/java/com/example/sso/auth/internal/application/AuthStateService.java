package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.Factors;
import com.example.sso.organization.OrganizationService;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Computes the SPA-facing authentication state by evaluating the user's resolved
 * authentication policy against the factors already satisfied in the session.
 */
@Service
@RequiredArgsConstructor
public class AuthStateService {

    private final UserService users;
    private final FactorHandlers factorHandlers;
    private final AuthPolicyResolver policyService;
    private final AuthPolicyEvaluator evaluator;
    private final OrgContext orgContext;
    private final OrganizationService organizations;

    /**
     * @param loginOrgId the tenant-first login org (null before it is resolved, or for post-login
     *                   step-up). Policy resolution binds it so the login org's own auth policies
     *                   (RLS-scoped) participate alongside the global/default policy.
     */
    public AuthSessionView describe(Authentication authentication, String activeOrgSlug, UUID loginOrgId) {
        // Whether the selected tenant permits passwordless passkey sign-in, so the SPA can offer it before
        // (and after) identifying the account. Enforced again server-side at login completion (zero trust).
        boolean passwordless = passwordlessAllowed(loginOrgId);
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return anonymous(activeOrgSlug, passwordless);
        }
        // Resolve the principal WITHIN the login's organization (the session's org isn't bound until MFA
        // completes), so an org-scoped user's factor state is read for the right account, not a same-named one.
        UserAccount user = users.findByUsernameInOrg(authentication.getName(), loginOrgId).orElse(null);
        if (user == null) {
            return anonymous(activeOrgSlug, passwordless);
        }

        Set<String> granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        List<String> factors = granted.stream().filter(a -> a.startsWith(Factors.FACTOR_PREFIX)).sorted().toList();
        List<String> roles = granted.stream().filter(a -> a.startsWith(Roles.ROLE_PREFIX)).sorted().toList();
        // Fine-grained permissions (resource:action) let the SPA gate admin nav by capability.
        List<String> permissions = granted.stream().filter(a -> a.contains(":")).sorted().toList();
        boolean totpEnrolled = factorHandlers.isEnrolled(AuthFactor.TOTP, user);
        boolean fido2Enrolled = factorHandlers.isEnrolled(AuthFactor.FIDO2, user);

        AuthPolicyView policy = resolvePolicy(user, loginOrgId);
        boolean enrollAllowed = policy.isAllowEnrollmentAtLogin(); // per the user's winning login policy
        Optional<AuthPolicyStepView> step = evaluator.currentStep(policy, granted);
        if (step.isEmpty()) {
            // First-login password reset is the LAST gate: an admin-created user given a TEMPORARY password
            // must set their own before the session is DONE. Reported here (the single source of session
            // state) so every surface — login completion AND the /api/auth/session re-poll — agrees, and
            // isPolicySatisfied stays false so completion never grants MFA_COMPLETE while it is pending.
            if (user.isPasswordResetRequired()) {
                return AuthSessionView.mustResetPassword(user.getUsername(), activeOrgSlug);
            }
            return AuthSessionView.complete(user.getUsername(), totpEnrolled, fido2Enrolled, factors, roles, permissions, enrollAllowed, activeOrgSlug, passwordless);
        }

        // Order by the factor's natural preference (PASSWORD, TOTP, EMAIL, FIDO2) so the SPA defaults
        // the choice to the most broadly usable method rather than alphabetically (which put FIDO2 first).
        // NB: this couples the SPA's default choice to AuthFactor's declaration order — reordering the
        // enum silently changes the default. Keep the enum ordered by preference.
        List<String> pending = step.get().getAllowedFactors().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(AuthFactor::name).toList();
        return AuthSessionView.pending(user.getUsername(), totpEnrolled, fido2Enrolled, factors, roles, permissions, pending, enrollAllowed, activeOrgSlug, passwordless);
    }

    /** Whether the resolved login org (if any) has an admin enabled passwordless passkey sign-in. */
    private boolean passwordlessAllowed(UUID loginOrgId) {
        return organizations.isPasswordlessLoginEnabled(loginOrgId);
    }

    /** True once the user's policy is fully satisfied (used to grant the MFA-complete marker). */
    public boolean isPolicySatisfied(Authentication authentication, UUID loginOrgId) {
        return AuthSessionView.NEXT_DONE.equals(describe(authentication, null, loginOrgId).next());
    }

    // Resolve within the LOGIN org so the tenant's own auth policies (RLS-scoped) participate in
    // resolution; with no org bound (pre-org steps / step-up) only global/default policies resolve.
    private AuthPolicyView resolvePolicy(UserAccount user, UUID loginOrgId) {
        return loginOrgId == null
                ? policyService.resolveForUser(user)
                : orgContext.callInOrg(loginOrgId, () -> policyService.resolveForUser(user));
    }

    private AuthSessionView anonymous(String activeOrgSlug, boolean passwordless) {
        // Tenant-first: collect the org slug, then the email, then the policy drives the factors. No user
        // yet, so report the default policy's enroll-at-login flag.
        boolean enroll = policyService.defaultPolicy().isAllowEnrollmentAtLogin();
        return activeOrgSlug == null
                ? AuthSessionView.organizationPending(enroll)
                : AuthSessionView.identifyPending(activeOrgSlug, enroll, passwordless);
    }
}
