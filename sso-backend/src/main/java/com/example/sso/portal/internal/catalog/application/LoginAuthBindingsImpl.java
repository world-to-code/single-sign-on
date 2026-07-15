package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.LoginAssignment;
import com.example.sso.authpolicy.policy.LoginAuthBindings;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a policy's login scope as {@code PORTAL/user} AUTH bindings in the {@code policy_binding} matrix,
 * mirroring {@link AppAuthBinding} (the RLS-critical slot mechanics are shared via {@link PolicyBindingSlot}).
 * Only the {@code auth_policy_id} field is touched — a user-portal session binding sharing the same all-subjects
 * row survives a login clear.
 */
@Service
@RequiredArgsConstructor
class LoginAuthBindingsImpl implements LoginAuthBindings {

    private static final AppType APP_TYPE = AppType.PORTAL;
    private static final String APP_ID = PortalApps.USER;

    private final PolicyBindingRepository bindings;
    private final PolicyBindingSlot slot;
    private final OrgTierGuard tierGuard;

    @Override
    @Transactional
    public void replaceForPolicy(UUID policyId, int priority, boolean appliesToLogin, Set<UUID> userIds,
            Set<UUID> roleIds, Set<AttributePredicate> attributes) {
        UUID org = tierGuard.currentTier();
        boolean allSubjects = appliesToLogin && userIds.isEmpty() && roleIds.isEmpty() && attributes.isEmpty();
        Set<UUID> wantedUsers = appliesToLogin ? userIds : Set.of();
        Set<UUID> wantedRoles = appliesToLogin ? roleIds : Set.of();
        Set<AttributePredicate> wantedAttributes = appliesToLogin ? attributes : Set.of();

        if (allSubjects) {
            upsert(null, null, org, policyId, priority);
        }
        for (UUID userId : wantedUsers) {
            upsert(SubjectType.USER, userId, org, policyId, priority);
        }
        for (UUID roleId : wantedRoles) {
            upsert(SubjectType.ROLE, roleId, org, policyId, priority);
        }
        for (AttributePredicate predicate : wantedAttributes) {
            upsertAttribute(predicate, org, policyId, priority);
        }
        for (PolicyBinding owned : ownedBindings(policyId, org)) {
            if (!isWanted(owned, allSubjects, wantedUsers, wantedRoles, wantedAttributes)) {
                clear(owned);
            }
        }
    }

    @Override
    @Transactional
    public void clearForPolicy(UUID policyId) {
        ownedBindings(policyId, tierGuard.currentTier()).forEach(this::clear);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LoginAssignment> describe(Collection<UUID> policyIds) {
        if (policyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> users = new HashMap<>();
        Map<UUID, Set<UUID>> roles = new HashMap<>();
        Map<UUID, Set<AttributePredicate>> predicates = new HashMap<>();
        Set<UUID> forLogin = new HashSet<>();
        for (PolicyBinding binding : bindings.findByAppTypeAndAppIdAndAuthPolicyIdIn(APP_TYPE, APP_ID, policyIds)) {
            UUID policyId = binding.getAuthPolicyId();
            forLogin.add(policyId);
            switch (binding.getSubjectType()) {
                case USER -> users.computeIfAbsent(policyId, k -> new HashSet<>()).add(binding.getSubjectId());
                case ROLE -> roles.computeIfAbsent(policyId, k -> new HashSet<>()).add(binding.getSubjectId());
                case ATTRIBUTE -> predicates.computeIfAbsent(policyId, k -> new HashSet<>()).add(predicateOf(binding));
                case GROUP -> { } // login scope never uses GROUP
                case null -> { }  // an all-subjects row carries no per-subject scope entry
            }
        }
        Map<UUID, LoginAssignment> scopes = new HashMap<>();
        for (UUID policyId : policyIds) {
            scopes.put(policyId, forLogin.contains(policyId)
                    ? new LoginAssignment(true, users.getOrDefault(policyId, Set.of()),
                            roles.getOrDefault(policyId, Set.of()), predicates.getOrDefault(policyId, Set.of()))
                    : LoginAssignment.none());
        }
        return scopes;
    }

    private boolean isWanted(PolicyBinding binding, boolean allSubjects, Set<UUID> users, Set<UUID> roles,
            Set<AttributePredicate> attributes) {
        return switch (binding.getSubjectType()) {
            case null -> allSubjects;
            case USER -> users.contains(binding.getSubjectId());
            case ROLE -> roles.contains(binding.getSubjectId());
            case ATTRIBUTE -> attributes.contains(predicateOf(binding));
            case GROUP -> false; // login scope never uses GROUP; treat any stray row as unwanted
        };
    }

    private AttributePredicate predicateOf(PolicyBinding binding) {
        return new AttributePredicate(binding.getSubjectAttrKey(), binding.getSubjectAttrValue());
    }

    /** Take over (or create) the given subject slot for this policy — last write wins over another policy; the
     *  atomic upsert also carries the policy's tie-break weight onto a taken-over row and is race-safe. */
    private void upsert(SubjectType subjectType, UUID subjectId, UUID org, UUID policyId, int priority) {
        PolicyBinding binding = subjectType == null
                ? PolicyBinding.forAllSubjects(APP_TYPE, APP_ID, org)
                : PolicyBinding.forSubject(APP_TYPE, APP_ID, subjectType, subjectId, org);
        applyAuth(binding, policyId, priority);
    }

    private void upsertAttribute(AttributePredicate predicate, UUID org, UUID policyId, int priority) {
        applyAuth(PolicyBinding.forAttribute(APP_TYPE, APP_ID, predicate.key(), predicate.value(), org),
                policyId, priority);
    }

    private void applyAuth(PolicyBinding binding, UUID policyId, int priority) {
        binding.assignAuthPolicy(policyId);
        binding.reprioritize(priority);
        slot.upsert(binding, PolicyAxis.AUTH);
    }

    private void clear(PolicyBinding binding) {
        binding.assignAuthPolicy(null);
        slot.deleteIfEmptyElseSave(binding); // a user-portal session binding on the same row survives
    }

    /** This policy's login bindings in the acting tier (RLS may also surface GLOBAL rows — keep only the tier's). */
    private List<PolicyBinding> ownedBindings(UUID policyId, UUID org) {
        return slot.inTier(bindings.findByAppTypeAndAppIdAndAuthPolicyIdIn(APP_TYPE, APP_ID, List.of(policyId)), org);
    }
}
