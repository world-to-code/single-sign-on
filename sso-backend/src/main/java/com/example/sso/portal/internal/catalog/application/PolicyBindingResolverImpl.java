package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.role.RoleRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * field (see {@link PolicyBindingResolver}). Walks candidates most-specific first (USER &gt; ATTRIBUTE &gt;
 * GROUP/ROLE &gt; all-subjects, then a tenant's own binding over a global one, then condition-count, priority and
 * a stable id tie-break) and returns the first whose referenced policy is enabled, so a disabled higher-specificity
 * binding never masks an enabled lower-specificity one. An ATTRIBUTE binding matches when the user satisfies ALL
 * of its conditions (an AND {@link AttributePredicateGroup}) against their effective per-tenant attributes.
 */
@Service
@RequiredArgsConstructor
class PolicyBindingResolverImpl implements PolicyBindingResolver {

    private final PolicyBindingRepository bindings;
    private final PolicyBindingConditions conditionGroups;
    private final UserGroupService userGroups;
    private final AuthPolicyResolver authPolicies;
    private final SessionPolicyService sessionPolicies;
    private final AttributeService attributes;

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthPolicyView> resolveAuthPolicy(UserAccount user, AppType appType, String appId) {
        return ordered(user, appType, appId, PolicyBinding::getAuthPolicyId, PolicyBinding::getPriority)
                .map(b -> authPolicies.highestPriorityEnabled(List.of(b.getAuthPolicyId())))
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionPolicyDetails> resolveSessionPolicy(UserAccount user, AppType appType, String appId) {
        return ordered(user, appType, appId, PolicyBinding::getSessionPolicyId, PolicyBinding::getSessionPriority)
                .map(b -> sessionPolicies.findById(b.getSessionPolicyId()).filter(SessionPolicyDetails::isEnabled))
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionPolicyDetails> resolveSessionPolicies(UserAccount user, AppType appType, String appId) {
        // Every enabled matching binding's policy — including the all-subjects org-wide one (subject_type null
        // matches everyone) — so floor-type controls can be composed across all of them. Ordered MOST-SPECIFIC
        // FIRST (same order as the singular resolve), so element 0 is the specificity winner.
        return ordered(user, appType, appId, PolicyBinding::getSessionPolicyId, PolicyBinding::getSessionPriority)
                .map(b -> sessionPolicies.findById(b.getSessionPolicyId()).filter(SessionPolicyDetails::isEnabled))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * The bindings for this app whose FIELD is set and whose subject matches the user, ordered most-specific
     * first. Each ATTRIBUTE binding's conditions are loaded ONCE (no N+1) so both the subject match and the
     * ordering read them; the user's effective attributes are loaded only when a predicate binding is present.
     */
    private Stream<PolicyBinding> ordered(UserAccount user, AppType appType, String appId,
            Function<PolicyBinding, UUID> field, ToIntFunction<PolicyBinding> priority) {
        UUID userId = user.getId();
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(userId));
        List<PolicyBinding> candidates = bindings.findByAppTypeAndAppId(appType, appId).stream()
                .filter(b -> field.apply(b) != null)
                .toList();
        Map<UUID, AttributePredicateGroup> groups = conditionGroups.groupsOf(candidates);
        // Load the user's effective attributes only when a real predicate binding (one with conditions) is present.
        List<Attribute> userAttributes = groups.isEmpty() ? List.of() : effectiveAttributes(userId, groupIds);
        return candidates.stream()
                .filter(b -> subjectMatches(b, userId, roleIds, groupIds, userAttributes, groups))
                .sorted(mostSpecificFirst(priority, groups));
    }

    /** The user's own attributes unioned with those inherited from the groups they belong to. Note the
     *  specificity consequence: an inherited group tag matches at the ATTRIBUTE tier, which OUTRANKS the group's
     *  own GROUP binding — tagging a group can raise or lower the policy its members resolve, by admin intent. */
    private List<Attribute> effectiveAttributes(UUID userId, Set<UUID> groupIds) {
        List<Attribute> effective = new ArrayList<>(attributes.attributesOf(EntityKind.USER, userId.toString()));
        if (!groupIds.isEmpty()) {
            effective.addAll(attributes.unionAttributesOf(EntityKind.GROUP,
                    groupIds.stream().map(UUID::toString).toList()));
        }
        return effective;
    }

    /**
     * Most-specific-first ordering: USER &gt; ATTRIBUTE(value op) &gt; ATTRIBUTE(key op) &gt; GROUP/ROLE &gt;
     * all-subjects, then a tenant's OWN binding over the GLOBAL one, then the number of ANDed conditions (a
     * narrower compound target wins within the same tier and tenancy), then the FIELD's own priority, then a
     * stable id tie-break. Condition-count sits BELOW orgRank so the tenant-first invariant always holds.
     */
    private Comparator<PolicyBinding> mostSpecificFirst(ToIntFunction<PolicyBinding> priority,
            Map<UUID, AttributePredicateGroup> groups) {
        return Comparator.comparingInt((PolicyBinding b) -> specificity(b, groups))
                .thenComparingInt(this::orgRank)
                .thenComparingInt(b -> conditionCount(b, groups))
                .thenComparingInt(priority)
                .thenComparing(PolicyBinding::getId)
                .reversed();
    }

    /** USER &gt; ATTRIBUTE(any value op) &gt; ATTRIBUTE(key ops only) &gt; role/group membership &gt; app-wide
     *  default. A value condition (department = X, or IN a list) is a more deliberate target than a key-presence
     *  one (department EXISTS). */
    private int specificity(PolicyBinding b, Map<UUID, AttributePredicateGroup> groups) {
        if (b.getSubjectType() == null) {
            return 1;
        }
        return switch (b.getSubjectType()) {
            case USER -> 5;
            case ATTRIBUTE -> hasValueOperator(groups.get(b.getId())) ? 4 : 3;
            case ROLE, GROUP -> 2;
        };
    }

    private boolean hasValueOperator(AttributePredicateGroup group) {
        return group != null && group.conditions().stream().anyMatch(c -> c.operator().targetsValue());
    }

    private int conditionCount(PolicyBinding b, Map<UUID, AttributePredicateGroup> groups) {
        AttributePredicateGroup group = groups.get(b.getId());
        return group == null ? 0 : group.conditions().size();
    }

    /** A tenant's OWN binding beats the GLOBAL one it inherits at the same subject specificity. */
    private int orgRank(PolicyBinding b) {
        return b.getOrgId() != null ? 1 : 0;
    }

    private boolean subjectMatches(PolicyBinding b, UUID userId, Set<UUID> roleIds, Set<UUID> groupIds,
            List<Attribute> userAttributes, Map<UUID, AttributePredicateGroup> groups) {
        if (b.getSubjectType() == null) {
            return true;
        }
        return switch (b.getSubjectType()) {
            case USER -> b.getSubjectId().equals(userId);
            case ROLE -> roleIds.contains(b.getSubjectId());
            case GROUP -> groupIds.contains(b.getSubjectId());
            case ATTRIBUTE -> {
                AttributePredicateGroup group = groups.get(b.getId());
                yield group != null && group.matches(userAttributes); // a condition-less binding matches nobody
            }
        };
    }
}
