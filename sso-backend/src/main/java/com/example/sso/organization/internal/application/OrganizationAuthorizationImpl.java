package com.example.sso.organization.internal.application;

import com.example.sso.customer.CustomerService;
import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link OrganizationAuthorization}: an actor administers an org iff EITHER they hold
 * {@code ROLE_ORG_ADMIN} and are a member of it, OR they hold {@code ROLE_CUSTOMER_ADMIN} and the org is a
 * branch of a customer they administer. Both paths are membership-gated (org membership / customer
 * membership) on top of the role, so a bare role grants nothing. Reuses {@link OrganizationService} and
 * {@link CustomerService} membership queries and the user module's role check.
 */
@Service
@RequiredArgsConstructor
class OrganizationAuthorizationImpl implements OrganizationAuthorization {

    private final UserService users;
    private final OrganizationService organizations;
    private final CustomerService customers;

    @Override
    public boolean canManage(UUID actorUserId, UUID orgId) {
        return (isOrgAdmin(actorUserId) && organizations.isMember(orgId, actorUserId))
                || (isCustomerAdmin(actorUserId)
                        && organizations.isBranchOf(orgId, customers.customersForUser(actorUserId)));
    }

    @Override
    public Set<UUID> scopedOrgIds(UUID actorUserId) {
        Set<UUID> scoped = new HashSet<>();
        if (isOrgAdmin(actorUserId)) {
            scoped.addAll(organizations.orgIdsForUser(actorUserId));
        }
        if (isCustomerAdmin(actorUserId)) {
            scoped.addAll(organizations.branchIdsForCustomers(customers.customersForUser(actorUserId)));
        }
        return scoped;
    }

    private boolean isOrgAdmin(UUID actorUserId) {
        return users.hasRole(actorUserId, Roles.ORG_ADMIN);
    }

    private boolean isCustomerAdmin(UUID actorUserId) {
        return users.hasRole(actorUserId, Roles.CUSTOMER_ADMIN);
    }
}
