package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.policy.AuthPolicyStepView;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.access.AppAccessQuery;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.account.UserAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-app sign-on policy resolution and step-up gating: the sign-on policy required for a user to access an
 * app — resolved from the unified {@code policy_binding} matrix (a per-subject binding beats the app-wide one
 * by specificity, then priority) — and whether the user currently satisfies it (all factors held plus a fresh
 * deliberate step-up). The app-auth bindings are written through {@link AppAuthBinding}.
 */
@Service
@RequiredArgsConstructor
class AppAccessResolver {

    private final PolicyBindingResolver bindings;
    private final AppAuthBinding appAuthBinding;
    private final AuthPolicyEvaluator evaluator;

    @Transactional(readOnly = true)
    AppAccess appAccess(AppAccessQuery query) {
        UserAccount user = query.user();
        // Resolved in the acting (host-derived) org context — the app launch always runs in the user's tenant,
        // so the binding matrix is scoped to that tenant plus GLOBAL, never another tenant's rows.
        Optional<AuthPolicyView> resolved = bindings.resolveAuthPolicy(user, query.appType(), query.appId());
        if (resolved.isEmpty()) {
            return new AppAccess(true, List.of());
        }

        AuthPolicyView policy = resolved.get();
        // 1) Acquire any factor the user does not yet hold.
        Optional<AuthPolicyStepView> missing = evaluator.currentStep(policy, query.grantedFactors());
        if (missing.isPresent()) {
            return new AppAccess(false, factorNames(missing.get()));
        }

        // 2) All factors held — require a fresh deliberate step-up for this app.
        Duration window = Duration.ofMinutes(policy.getStepUpFreshnessMinutes());
        Instant lastAppStepUp = query.lastAppStepUp();
        boolean fresh = lastAppStepUp != null && !Duration.between(lastAppStepUp, Instant.now()).minus(window).isPositive();
        if (fresh || policy.getSteps().isEmpty()) {
            return new AppAccess(true, List.of());
        }

        // Re-prove the final (strongest) step to refresh the window.
        AuthPolicyStepView last = policy.getSteps().get(policy.getSteps().size() - 1);
        return new AppAccess(false, factorNames(last));
    }

    /** Sets (or, when blank/null, clears) the app-wide sign-on policy for an app — an all-subjects auth binding. */
    @Transactional
    void setAppPolicy(AppType appType, String appId, String requiredPolicyId) {
        if (appType == AppType.PORTAL) {
            // A portal is not a catalog app: end-user login resolves the PORTAL/user auth binding and the console
            // logs in through the admin-console OIDC client, so neither reads a PORTAL sign-on binding. Reject here
            // (as assignment does) so this write path cannot leave an orphan PORTAL auth binding nothing governs.
            throw BadRequestException.of("portal.policy.notApplicable");
        }
        UUID policyId = requiredPolicyId == null || requiredPolicyId.isBlank() ? null : UUID.fromString(requiredPolicyId);
        appAuthBinding.setAppWide(appType, appId, policyId);
    }

    private List<String> factorNames(AuthPolicyStepView step) {
        return step.getAllowedFactors().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(AuthFactor::name).toList();
    }
}
