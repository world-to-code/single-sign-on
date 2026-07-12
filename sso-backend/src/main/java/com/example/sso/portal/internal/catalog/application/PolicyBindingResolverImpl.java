package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.role.RoleRef;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the {@code policy_binding} matrix and returns the most specific matching, still-enabled binding per
 * field (see {@link PolicyBindingResolver}). Walks candidates most-specific first (USER &gt; GROUP/ROLE &gt;
 * all-subjects, then priority, then a stable id tie-break) and returns the first whose referenced policy is
 * enabled, so a disabled higher-specificity binding never masks an enabled lower-specificity one.
 */
@Service
@RequiredArgsConstructor
class PolicyBindingResolverImpl implements PolicyBindingResolver {

    private final PolicyBindingRepository bindings;
    private final UserGroupService userGroups;
    private final AuthPolicyResolver authPolicies;
    private final SessionPolicyService sessionPolicies;

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthPolicyView> resolveAuthPolicy(UserAccount user, AppType appType, String appId) {
        return resolveField(user, appType, appId, PolicyBinding::getAuthPolicyId,
                id -> authPolicies.highestPriorityEnabled(List.of(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionPolicyDetails> resolveSessionPolicy(UserAccount user, AppType appType, String appId) {
        return resolveField(user, appType, appId, PolicyBinding::getSessionPolicyId,
                id -> sessionPolicies.findById(id).filter(SessionPolicyDetails::isEnabled));
    }

    private <P> Optional<P> resolveField(UserAccount user, AppType appType, String appId,
            Function<PolicyBinding, UUID> field, Function<UUID, Optional<P>> load) {
        UUID userId = user.getId();
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(userId));
        return bindings.findByAppTypeAndAppId(appType, appId).stream()
                .filter(b -> field.apply(b) != null)
                .filter(b -> subjectMatches(b, userId, roleIds, groupIds))
                .sorted(Comparator.comparingInt(this::specificity)
                        .thenComparingInt(PolicyBinding::getPriority)
                        .thenComparing(PolicyBinding::getId)
                        .reversed())
                .map(b -> load.apply(field.apply(b)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** USER is the most specific, then a role/group membership, then the app-wide (all-subjects) default. */
    private int specificity(PolicyBinding b) {
        if (b.getSubjectType() == null) {
            return 1;
        }
        return b.getSubjectType() == PolicyBinding.SubjectType.USER ? 3 : 2;
    }

    private boolean subjectMatches(PolicyBinding b, UUID userId, Set<UUID> roleIds, Set<UUID> groupIds) {
        if (b.getSubjectType() == null) {
            return true;
        }
        return switch (b.getSubjectType()) {
            case USER -> b.getSubjectId().equals(userId);
            case ROLE -> roleIds.contains(b.getSubjectId());
            case GROUP -> groupIds.contains(b.getSubjectId());
        };
    }
}
