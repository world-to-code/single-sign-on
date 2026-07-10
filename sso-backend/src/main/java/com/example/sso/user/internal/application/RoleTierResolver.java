package com.example.sso.user.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RoleRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The ONE place that resolves a role NAME to a role within a tier: the tier's own (provisioned or custom)
 * role first, else the GLOBAL role of that name. Both the authorization check (may this admin hand out
 * this role?) and the assignment itself resolve through this, so the role that is CHECKED is always the
 * role that gets ASSIGNED — a split between the two is a grant-only-what-you-hold bypass.
 */
@Component
@RequiredArgsConstructor
class RoleTierResolver {

    private final RoleRepository roles;
    private final OrgContext orgContext;

    /**
     * The role named {@code name} in {@code orgId}'s tier, else the global one; empty when neither exists.
     * The org lookup runs in the org's scope — {@code role} is RLS-forced and a caller may be unbound
     * (self-signup, async onboarding, a background thread), where a bare query silently misses the org row.
     */
    Optional<Role> resolve(String name, UUID orgId) {
        return (orgId == null
                ? Optional.<Role>empty()
                : orgContext.callInOrg(orgId, () -> roles.findByNameAndOrgId(name, orgId)))
                .or(() -> roles.findByNameAndOrgIdIsNull(name));
    }
}
