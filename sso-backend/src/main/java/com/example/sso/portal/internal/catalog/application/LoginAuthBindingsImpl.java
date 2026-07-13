package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.LoginAssignment;
import com.example.sso.authpolicy.policy.LoginAuthBindings;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a policy's login scope as {@code PORTAL/user} AUTH bindings in the {@code policy_binding} matrix,
 * mirroring {@link AppAuthBinding} (org-stamped with the acting tier, saveAndFlush in-scope so RLS confines
 * a tenant admin's write to its own org). Only the {@code auth_policy_id} field is touched — a user-portal
 * session binding sharing the same all-subjects row survives a login clear.
 */
@Service
@RequiredArgsConstructor
class LoginAuthBindingsImpl implements LoginAuthBindings {

    private static final AppType APP_TYPE = AppType.PORTAL;
    private static final String APP_ID = PortalApps.USER;

    private final PolicyBindingRepository bindings;
    private final OrgTierGuard tierGuard;

    @Override
    @Transactional
    public void replaceForPolicy(UUID policyId, int priority, boolean appliesToLogin, Set<UUID> userIds, Set<UUID> roleIds) {
        UUID org = tierGuard.currentTier();
        boolean allSubjects = appliesToLogin && userIds.isEmpty() && roleIds.isEmpty();
        Set<UUID> wantedUsers = appliesToLogin ? userIds : Set.of();
        Set<UUID> wantedRoles = appliesToLogin ? roleIds : Set.of();

        if (allSubjects) {
            upsert(null, null, org, policyId, priority);
        }
        for (UUID userId : wantedUsers) {
            upsert(SubjectType.USER, userId, org, policyId, priority);
        }
        for (UUID roleId : wantedRoles) {
            upsert(SubjectType.ROLE, roleId, org, policyId, priority);
        }
        for (PolicyBinding owned : ownedBindings(policyId, org)) {
            if (!isWanted(owned, allSubjects, wantedUsers, wantedRoles)) {
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
        Set<UUID> forLogin = new HashSet<>();
        for (PolicyBinding binding : bindings.findByAppTypeAndAppIdAndAuthPolicyIdIn(APP_TYPE, APP_ID, policyIds)) {
            UUID policyId = binding.getAuthPolicyId();
            forLogin.add(policyId);
            if (binding.getSubjectType() == SubjectType.USER) {
                users.computeIfAbsent(policyId, k -> new HashSet<>()).add(binding.getSubjectId());
            } else if (binding.getSubjectType() == SubjectType.ROLE) {
                roles.computeIfAbsent(policyId, k -> new HashSet<>()).add(binding.getSubjectId());
            }
        }
        Map<UUID, LoginAssignment> scopes = new HashMap<>();
        for (UUID policyId : policyIds) {
            scopes.put(policyId, forLogin.contains(policyId)
                    ? new LoginAssignment(true, users.getOrDefault(policyId, Set.of()), roles.getOrDefault(policyId, Set.of()))
                    : LoginAssignment.none());
        }
        return scopes;
    }

    private boolean isWanted(PolicyBinding binding, boolean allSubjects, Set<UUID> users, Set<UUID> roles) {
        return switch (binding.getSubjectType()) {
            case null -> allSubjects;
            case USER -> users.contains(binding.getSubjectId());
            case ROLE -> roles.contains(binding.getSubjectId());
            case GROUP -> false; // login scope never uses GROUP; treat any stray row as unwanted
        };
    }

    /** Take over (or create) the given subject slot for this policy — last write wins over another policy. */
    private void upsert(SubjectType subjectType, UUID subjectId, UUID org, UUID policyId, int priority) {
        PolicyBinding binding = row(subjectType, subjectId, org).orElseGet(() -> PolicyBinding.builder()
                .appType(APP_TYPE).appId(APP_ID).subjectType(subjectType).subjectId(subjectId)
                .authPolicyId(policyId).priority(priority).orgId(org).build());
        binding.assignAuthPolicy(policyId);
        binding.reprioritize(priority); // carry the policy's tie-break weight onto a taken-over row too
        bindings.saveAndFlush(binding); // flush in the acting tier so RLS WITH CHECK sees the right org
    }

    private void clear(PolicyBinding binding) {
        binding.assignAuthPolicy(null);
        if (binding.carriesNoPolicy()) {
            bindings.delete(binding);
        } else {
            bindings.saveAndFlush(binding); // a user-portal session binding on the same row survives
        }
    }

    /** This policy's login bindings in the acting tier (RLS may also surface GLOBAL rows — keep only the tier's). */
    private List<PolicyBinding> ownedBindings(UUID policyId, UUID org) {
        return bindings.findByAppTypeAndAppIdAndAuthPolicyIdIn(APP_TYPE, APP_ID, List.of(policyId)).stream()
                .filter(binding -> Objects.equals(binding.getOrgId(), org))
                .toList();
    }

    private Optional<PolicyBinding> row(SubjectType subjectType, UUID subjectId, UUID org) {
        if (subjectType == null) {
            return org == null
                    ? bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgIdIsNull(APP_TYPE, APP_ID)
                    : bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgId(APP_TYPE, APP_ID, org);
        }
        return org == null
                ? bindings.findByAppTypeAndAppIdAndSubjectTypeAndSubjectIdAndOrgIdIsNull(APP_TYPE, APP_ID, subjectType, subjectId)
                : bindings.findByAppTypeAndAppIdAndSubjectTypeAndSubjectIdAndOrgId(APP_TYPE, APP_ID, subjectType, subjectId, org);
    }
}
