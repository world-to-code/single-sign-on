package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PortalSessionBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.shared.error.BadRequestException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a portal's governing session policy — the all-subjects {@code PORTAL}/{@code <appId>} row in
 * {@code policy_binding} — parameterised by the portal's {@code appId}. The admin console and the end-user portal
 * share this ONE implementation so their isolation and tier rules can never diverge (a divergence between them
 * was the concrete risk called out when the admin binding was first written).
 *
 * <p>Resolution reads the acting tenant's own row, else the GLOBAL default it inherits; writes touch only the
 * acting tenant's own row, and only the platform context may edit the global default. The selected policy must
 * be visible in the tenant's own tier — otherwise a portal could be governed by a policy the tenant neither
 * owns nor can inspect.
 */
@Service
@RequiredArgsConstructor
class PortalSessionBindingImpl implements PortalSessionBinding {

    private final PolicyBindingRepository bindings;
    private final SessionPolicyService sessionPolicies;
    private final PortalOrgScope orgScope;

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> sessionPolicyId(String appId) {
        // own-else-global mirrors PolicyBindingResolverImpl#orgRank (a tenant's own binding beats the inherited
        // global); keep the two in step if a tenancy tier is ever inserted between org and global.
        return ownRow(appId, orgScope.actingOrg()).or(() -> globalRow(appId)).map(PolicyBinding::getSessionPolicyId);
    }

    @Override
    @Transactional
    public void setSessionPolicy(String appId, UUID sessionPolicyId) {
        requireSelectableInTier(sessionPolicyId);
        UUID org = orgScope.writableOrg();
        Optional<PolicyBinding> existing = ownRow(appId, org);
        if (sessionPolicyId == null) {
            existing.ifPresent(bindings::delete); // clearing restores the inherited/own resolved policy
            return;
        }
        PolicyBinding row = existing.orElseGet(() -> PolicyBinding.builder()
                .appType(AppType.PORTAL).appId(appId).sessionPolicyId(sessionPolicyId).orgId(org).build());
        row.assignSessionPolicy(sessionPolicyId);
        bindings.saveAndFlush(row); // flush in the acting org scope so RLS WITH CHECK sees the right tenant
    }

    /**
     * A tenant may only point a portal at a policy of its OWN tier: {@code listAll} is tier-scoped, so a
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

    private Optional<PolicyBinding> ownRow(String appId, UUID org) {
        return org == null ? globalRow(appId)
                : bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgId(AppType.PORTAL, appId, org);
    }

    private Optional<PolicyBinding> globalRow(String appId) {
        return bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgIdIsNull(AppType.PORTAL, appId);
    }
}
