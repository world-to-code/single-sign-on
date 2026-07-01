package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.auth.internal.application.FactorHandlers;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes the SPA-facing authentication state by evaluating the user's resolved
 * authentication policy against the factors already satisfied in the session.
 */
@Service
public class AuthStateService {

    private final UserService users;
    private final FactorHandlers factorHandlers;
    private final AuthPolicyResolver policyService;
    private final AuthPolicyEvaluator evaluator;

    public AuthStateService(UserService users, FactorHandlers factorHandlers,
                            AuthPolicyResolver policyService, AuthPolicyEvaluator evaluator) {
        this.users = users;
        this.factorHandlers = factorHandlers;
        this.policyService = policyService;
        this.evaluator = evaluator;
    }

    public AuthSessionView describe(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return anonymous();
        }
        UserAccount user = users.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            return anonymous();
        }

        Set<String> granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        List<String> factors = granted.stream().filter(a -> a.startsWith("FACTOR_")).sorted().toList();
        List<String> roles = granted.stream().filter(a -> a.startsWith("ROLE_")).sorted().toList();
        boolean totpEnrolled = factorHandlers.isEnrolled(AuthFactor.TOTP, user);
        boolean fido2Enrolled = factorHandlers.isEnrolled(AuthFactor.FIDO2, user);

        AuthPolicyView policy = policyService.resolveForUser(user);
        boolean enrollAllowed = policy.isAllowEnrollmentAtLogin(); // per the user's winning login policy
        Optional<AuthPolicyStepView> step = evaluator.currentStep(policy, granted);
        if (step.isEmpty()) {
            return new AuthSessionView(true, user.getUsername(), totpEnrolled, fido2Enrolled, factors, roles, "DONE", List.of(), enrollAllowed);
        }

        // Order by the factor's natural preference (PASSWORD, TOTP, EMAIL, FIDO2) so the SPA defaults
        // the choice to the most broadly usable method rather than alphabetically (which put FIDO2 first).
        List<String> pending = step.get().getAllowedFactors().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(AuthFactor::name).toList();
        return new AuthSessionView(false, user.getUsername(), totpEnrolled, fido2Enrolled, factors, roles, "FACTOR", pending, enrollAllowed);
    }

    /** True once the user's policy is fully satisfied (used to grant the MFA-complete marker). */
    public boolean isPolicySatisfied(Authentication authentication) {
        return "DONE".equals(describe(authentication).next());
    }

    private AuthSessionView anonymous() {
        // Identifier-first: the SPA collects the email, then the policy drives the factors. No user yet,
        // so report the default policy's enroll-at-login flag.
        return new AuthSessionView(false, null, false, false, List.of(), List.of(), "IDENTIFY", List.of(),
                policyService.defaultPolicy().isAllowEnrollmentAtLogin());
    }
}
