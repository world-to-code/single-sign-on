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
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
        return resolveField(user, appType, appId, PolicyBinding::getAuthPolicyId, PolicyBinding::getPriority,
                id -> authPolicies.highestPriorityEnabled(List.of(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionPolicyDetails> resolveSessionPolicy(UserAccount user, AppType appType, String appId) {
        return resolveField(user, appType, appId, PolicyBinding::getSessionPolicyId, PolicyBinding::getSessionPriority,
                id -> sessionPolicies.findById(id).filter(SessionPolicyDetails::isEnabled));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionPolicyDetails> resolveSessionPolicies(UserAccount user, AppType appType, String appId) {
        // Every enabled matching binding's policy — including the all-subjects org-wide one (subject_type null
        // matches everyone) — so floor-type controls can be composed across all of them, not just the winner.
        return matching(user, appType, appId, PolicyBinding::getSessionPolicyId)
                .map(b -> sessionPolicies.findById(b.getSessionPolicyId()).filter(SessionPolicyDetails::isEnabled))
                .flatMap(Optional::stream)
                .toList();
    }

    private <P> Optional<P> resolveField(UserAccount user, AppType appType, String appId,
            Function<PolicyBinding, UUID> field, ToIntFunction<PolicyBinding> priority,
            Function<UUID, Optional<P>> load) {
        // Tie-break on the FIELD's own priority (auth vs session), not a shared column: a co-located row carries
        // an independently-assigned auth policy and session policy, each with its own weight.
        return matching(user, appType, appId, field)
                .sorted(Comparator.comparingInt(this::specificity)
                        .thenComparingInt(this::orgRank)
                        .thenComparingInt(priority)
                        .thenComparing(PolicyBinding::getId)
                        .reversed())
                .map(b -> load.apply(field.apply(b)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** The bindings for this app whose FIELD is set and whose subject matches the user (RLS-scoped by context). */
    private Stream<PolicyBinding> matching(UserAccount user, AppType appType, String appId,
            Function<PolicyBinding, UUID> field) {
        UUID userId = user.getId();
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(userId));
        return bindings.findByAppTypeAndAppId(appType, appId).stream()
                .filter(b -> field.apply(b) != null)
                .filter(b -> subjectMatches(b, userId, roleIds, groupIds));
    }

    /** USER is the most specific, then a role/group membership, then the app-wide (all-subjects) default. */
    private int specificity(PolicyBinding b) {
        if (b.getSubjectType() == null) {
            return 1;
        }
        return b.getSubjectType() == PolicyBinding.SubjectType.USER ? 3 : 2;
    }

    /** A tenant's OWN binding beats the GLOBAL one it inherits at the same subject specificity. */
    private int orgRank(PolicyBinding b) {
        return b.getOrgId() != null ? 1 : 0;
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
