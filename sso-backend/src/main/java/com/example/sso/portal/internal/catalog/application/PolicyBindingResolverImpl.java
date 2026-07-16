package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.metadata.Attribute;
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
 * GROUP/ROLE &gt; all-subjects, then priority, then a stable id tie-break) and returns the first whose referenced
 * policy is enabled, so a disabled higher-specificity binding never masks an enabled lower-specificity one. An
 * ATTRIBUTE binding matches when the user carries its metadata predicate (its effective per-tenant attributes).
 */
@Service
@RequiredArgsConstructor
class PolicyBindingResolverImpl implements PolicyBindingResolver {

    private final PolicyBindingRepository bindings;
    private final UserGroupService userGroups;
    private final AuthPolicyResolver authPolicies;
    private final SessionPolicyService sessionPolicies;
    private final AttributeService attributes;

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
        // matches everyone) — so floor-type controls can be composed across all of them. Ordered MOST-SPECIFIC
        // FIRST (same order as resolveField), so element 0 is the specificity winner and the caller can derive
        // both the winner and the floor from one resolution.
        return matching(user, appType, appId, PolicyBinding::getSessionPolicyId)
                .sorted(mostSpecificFirst(PolicyBinding::getSessionPriority))
                .map(b -> sessionPolicies.findById(b.getSessionPolicyId()).filter(SessionPolicyDetails::isEnabled))
                .flatMap(Optional::stream)
                .toList();
    }

    private <P> Optional<P> resolveField(UserAccount user, AppType appType, String appId,
            Function<PolicyBinding, UUID> field, ToIntFunction<PolicyBinding> priority,
            Function<UUID, Optional<P>> load) {
        return matching(user, appType, appId, field)
                .sorted(mostSpecificFirst(priority))
                .map(b -> load.apply(field.apply(b)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Most-specific-first ordering: USER &gt; ATTRIBUTE &gt; GROUP/ROLE &gt; all-subjects, then a tenant's OWN
     * binding over the GLOBAL one, then the FIELD's own priority (auth vs session — a co-located row carries an
     * independently assigned auth policy and session policy, each with its own weight), then a stable id tie-break.
     */
    private Comparator<PolicyBinding> mostSpecificFirst(ToIntFunction<PolicyBinding> priority) {
        return Comparator.comparingInt(this::specificity)
                .thenComparingInt(this::orgRank)
                .thenComparingInt(priority)
                .thenComparing(PolicyBinding::getId)
                .reversed();
    }

    /** The bindings for this app whose FIELD is set and whose subject matches the user (RLS-scoped by context). */
    private Stream<PolicyBinding> matching(UserAccount user, AppType appType, String appId,
            Function<PolicyBinding, UUID> field) {
        UUID userId = user.getId();
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(userId));
        List<PolicyBinding> candidates = bindings.findByAppTypeAndAppId(appType, appId).stream()
                .filter(b -> field.apply(b) != null)
                .toList();
        // Load the user's effective attributes ONCE, and only when a predicate binding is actually present (each
        // attributesOf is a query). A predicate matches on the user's OWN attributes OR any of their groups' — a
        // group tag is inherited by its members (union; groupIds are already RLS-scoped, so no cross-tenant leak).
        List<Attribute> userAttributes = candidates.stream().anyMatch(this::isAttributeBinding)
                ? effectiveAttributes(userId, groupIds)
                : List.of();
        return candidates.stream().filter(b -> subjectMatches(b, userId, roleIds, groupIds, userAttributes));
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

    /** USER &gt; ATTRIBUTE(value op) &gt; ATTRIBUTE(key op) &gt; role/group membership &gt; app-wide default. A value
     *  predicate (department = X) is a more deliberate target than a key-presence one (department EXISTS). */
    private int specificity(PolicyBinding b) {
        if (b.getSubjectType() == null) {
            return 1;
        }
        return switch (b.getSubjectType()) {
            case USER -> 5;
            case ATTRIBUTE -> b.getSubjectAttrOp().requiresValue() ? 4 : 3;
            case ROLE, GROUP -> 2;
        };
    }

    /** A tenant's OWN binding beats the GLOBAL one it inherits at the same subject specificity. */
    private int orgRank(PolicyBinding b) {
        return b.getOrgId() != null ? 1 : 0;
    }

    private boolean subjectMatches(PolicyBinding b, UUID userId, Set<UUID> roleIds, Set<UUID> groupIds,
            List<Attribute> userAttributes) {
        if (b.getSubjectType() == null) {
            return true;
        }
        return switch (b.getSubjectType()) {
            case USER -> b.getSubjectId().equals(userId);
            case ROLE -> roleIds.contains(b.getSubjectId());
            case GROUP -> groupIds.contains(b.getSubjectId());
            case ATTRIBUTE -> b.subjectPredicate().matches(userAttributes);
        };
    }

    private boolean isAttributeBinding(PolicyBinding b) {
        return b.getSubjectType() == PolicyBinding.SubjectType.ATTRIBUTE;
    }
}
