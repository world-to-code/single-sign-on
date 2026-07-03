package com.example.sso.portal.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.portal.AppAccess;
import com.example.sso.portal.AppAccessQuery;
import com.example.sso.portal.AppType;
import com.example.sso.portal.internal.domain.AppAssignment;
import com.example.sso.portal.internal.domain.AppAssignmentRepository;
import com.example.sso.portal.internal.domain.AppPolicy;
import com.example.sso.portal.internal.domain.AppPolicyRepository;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.RoleRef;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-app sign-on policy resolution and step-up gating: the highest-priority enabled policy that applies
 * to a user for an app (app-level plus per-subject assignment policy), and whether they currently satisfy
 * it (all factors held plus a fresh deliberate step-up).
 */
@Service
@RequiredArgsConstructor
class AppAccessResolver {

    private final AppAssignmentRepository assignments;
    private final AppPolicyRepository appPolicies;
    private final AuthPolicyResolver authPolicies;
    private final AuthPolicyEvaluator evaluator;
    private final UserGroupService userGroups;

    @Transactional(readOnly = true)
    AppAccess appAccess(AppAccessQuery query) {
        UserAccount user = query.user();
        Optional<AuthPolicyView> resolved = resolveAppPolicy(user, query.appType(), query.appId());
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

    @Transactional
    void setAppPolicy(AppType appType, String appId, String requiredPolicyId) {
        appPolicies.deleteByAppTypeAndAppId(appType, appId); // one policy per app: replace any existing

        if (requiredPolicyId != null && !requiredPolicyId.isBlank()) {
            UUID policyId = UUID.fromString(requiredPolicyId);
            if (!authPolicies.exists(policyId)) {
                throw new NotFoundException("policy not found");
            }

            appPolicies.save(new AppPolicy(appType, appId, policyId));
        }
    }

    private List<String> factorNames(AuthPolicyStepView step) {
        return step.getAllowedFactors().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(AuthFactor::name).toList();
    }

    /**
     * The highest-priority enabled policy required to access this app: the app-level sign-on policy
     * (applies to everyone) plus any per-subject assignment policy matching the user (directly/via role or group).
     */
    private Optional<AuthPolicyView> resolveAppPolicy(UserAccount user, AppType appType, String appId) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(user.getId()));
        List<UUID> candidateIds = new ArrayList<>();

        assignments.findByAppTypeAndAppId(appType, appId).stream()
                .filter(a -> a.getRequiredPolicyId() != null)
                .filter(a -> subjectMatches(a, user.getId(), roleIds, groupIds))
                .forEach(a -> candidateIds.add(a.getRequiredPolicyId()));
        appPolicies.findByAppTypeAndAppId(appType, appId).ifPresent(ap -> candidateIds.add(ap.getRequiredPolicyId()));

        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }

        return authPolicies.highestPriorityEnabled(candidateIds);
    }

    private boolean subjectMatches(AppAssignment a, UUID userId, Set<UUID> roleIds, Set<UUID> groupIds) {
        return switch (a.getSubjectType()) {
            case USER -> a.getSubjectId().equals(userId);
            case ROLE -> roleIds.contains(a.getSubjectId());
            case GROUP -> groupIds.contains(a.getSubjectId());
        };
    }
}
