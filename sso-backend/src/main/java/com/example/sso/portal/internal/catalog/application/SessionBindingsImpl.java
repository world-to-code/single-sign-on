package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionAssignment;
import com.example.sso.session.policy.SessionBindings;
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
 * Stores a session policy's assignment scope as {@code PORTAL/user} SESSION bindings in the {@code policy_binding}
 * matrix, mirroring {@link LoginAuthBindings} (org-stamped with the acting tier, saveAndFlush in-scope so RLS
 * confines a tenant admin's write to its own org). Only the {@code session_policy_id}/{@code session_priority}
 * fields are touched — a login (auth) binding sharing the same all-subjects/per-subject row survives.
 */
@Service
@RequiredArgsConstructor
class SessionBindingsImpl implements SessionBindings {

    private static final AppType APP_TYPE = AppType.PORTAL;
    private static final String APP_ID = PortalApps.USER;

    private final PolicyBindingRepository bindings;
    private final OrgTierGuard tierGuard;

    @Override
    @Transactional
    public void replaceForPolicy(UUID policyId, int priority, Set<UUID> userIds, Set<UUID> roleIds) {
        UUID org = tierGuard.currentTier();
        boolean allSubjects = userIds.isEmpty() && roleIds.isEmpty();

        if (allSubjects) {
            upsert(null, null, org, policyId, priority);
        }
        for (UUID userId : userIds) {
            upsert(SubjectType.USER, userId, org, policyId, priority);
        }
        for (UUID roleId : roleIds) {
            upsert(SubjectType.ROLE, roleId, org, policyId, priority);
        }
        for (PolicyBinding owned : ownedBindings(policyId, org)) {
            if (!isWanted(owned, allSubjects, userIds, roleIds)) {
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
    public Map<UUID, SessionAssignment> describe(Collection<UUID> policyIds) {
        if (policyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> users = new HashMap<>();
        Map<UUID, Set<UUID>> roles = new HashMap<>();
        for (PolicyBinding binding : bindings.findByAppTypeAndAppIdAndSessionPolicyIdIn(APP_TYPE, APP_ID, policyIds)) {
            UUID policyId = binding.getSessionPolicyId();
            if (binding.getSubjectType() == SubjectType.USER) {
                users.computeIfAbsent(policyId, k -> new HashSet<>()).add(binding.getSubjectId());
            } else if (binding.getSubjectType() == SubjectType.ROLE) {
                roles.computeIfAbsent(policyId, k -> new HashSet<>()).add(binding.getSubjectId());
            }
        }
        Map<UUID, SessionAssignment> scopes = new HashMap<>();
        for (UUID policyId : policyIds) {
            scopes.put(policyId, new SessionAssignment(
                    users.getOrDefault(policyId, Set.of()), roles.getOrDefault(policyId, Set.of())));
        }
        return scopes;
    }

    private boolean isWanted(PolicyBinding binding, boolean allSubjects, Set<UUID> users, Set<UUID> roles) {
        return switch (binding.getSubjectType()) {
            case null -> allSubjects;
            case USER -> users.contains(binding.getSubjectId());
            case ROLE -> roles.contains(binding.getSubjectId());
            case GROUP -> false; // session scope never uses GROUP; treat any stray row as unwanted
        };
    }

    /** Take over (or create) the given subject slot for this policy — last write wins over another policy. */
    private void upsert(SubjectType subjectType, UUID subjectId, UUID org, UUID policyId, int priority) {
        PolicyBinding binding = row(subjectType, subjectId, org).orElseGet(() -> PolicyBinding.builder()
                .appType(APP_TYPE).appId(APP_ID).subjectType(subjectType).subjectId(subjectId)
                .sessionPolicyId(policyId).sessionPriority(priority).orgId(org).build());
        binding.assignSessionPolicy(policyId);
        binding.reprioritizeSession(priority); // session tie-break weight, independent of a co-located auth binding
        bindings.saveAndFlush(binding); // flush in the acting tier so RLS WITH CHECK sees the right org
    }

    private void clear(PolicyBinding binding) {
        binding.assignSessionPolicy(null);
        if (binding.carriesNoPolicy()) {
            bindings.delete(binding);
        } else {
            bindings.saveAndFlush(binding); // a login (auth) binding on the same row survives
        }
    }

    /** This policy's session bindings in the acting tier (RLS may also surface GLOBAL rows — keep only the tier's). */
    private List<PolicyBinding> ownedBindings(UUID policyId, UUID org) {
        return bindings.findByAppTypeAndAppIdAndSessionPolicyIdIn(APP_TYPE, APP_ID, List.of(policyId)).stream()
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
