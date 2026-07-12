package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.AdminConsoleBinding;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the acting tenant's admin-console session binding ({@code PORTAL}/{@code admin},
 * all-subjects) in {@code policy_binding}. Resolution reads the tenant's own row, else the GLOBAL default;
 * writes touch only the acting tenant's own row (a super-admin in the platform context edits the global one).
 * The selected policy must be visible in the tenant's own tier — otherwise the console could be governed by a
 * policy the tenant neither owns nor can inspect.
 */
@Service
@RequiredArgsConstructor
class AdminConsoleBindingImpl implements AdminConsoleBinding {

    private final PolicyBindingRepository bindings;
    private final SessionPolicyService sessionPolicies;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> sessionPolicyId() {
        // own-else-global mirrors PolicyBindingResolverImpl#orgRank (a tenant's own binding beats the inherited
        // global); keep the two in step if a tenancy tier is ever inserted between org and global.
        UUID org = orgContext.currentOrg().orElse(null);
        return ownRow(org).or(this::globalRow).map(PolicyBinding::getSessionPolicyId);
    }

    @Override
    @Transactional
    public void setSessionPolicy(UUID sessionPolicyId) {
        requireSelectableInTier(sessionPolicyId);
        UUID org = writableOrg();
        Optional<PolicyBinding> existing = ownRow(org);
        if (sessionPolicyId == null) {
            existing.ifPresent(bindings::delete); // clearing restores "the acting admin's own resolved policy"
            return;
        }
        PolicyBinding row = existing.orElseGet(() -> PolicyBinding.builder()
                .appType(AppType.PORTAL).appId(PortalApps.ADMIN).sessionPolicyId(sessionPolicyId).orgId(org).build());
        row.assignSessionPolicy(sessionPolicyId);
        bindings.saveAndFlush(row); // flush in the acting org scope so RLS WITH CHECK sees the right tenant
    }

    /**
     * A tenant may only point its console at a policy of its OWN tier: {@code listAll} is tier-scoped, so a
     * policy outside it (another tenant's, or a global one a tenant cannot see) is rejected.
     */
    private void requireSelectableInTier(UUID policyId) {
        if (policyId == null) {
            return;
        }
        boolean selectable = sessionPolicies.listAll().stream()
                .map(SessionPolicyDetails::getId).anyMatch(policyId::equals);
        if (!selectable) {
            throw BadRequestException.of("admin.sessionPolicy.unknown");
        }
    }

    /** The acting org for a write: the bound tenant, or null for the platform super-admin editing the global. */
    private UUID writableOrg() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            // Deny-by-default: only the platform context may edit the global default every tenant inherits.
            throw new ForbiddenException("only a platform administrator may edit the global admin-console policy");
        }
        return org;
    }

    private Optional<PolicyBinding> ownRow(UUID org) {
        return org == null ? globalRow()
                : bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgId(AppType.PORTAL, PortalApps.ADMIN, org);
    }

    private Optional<PolicyBinding> globalRow() {
        return bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgIdIsNull(AppType.PORTAL, PortalApps.ADMIN);
    }
}
