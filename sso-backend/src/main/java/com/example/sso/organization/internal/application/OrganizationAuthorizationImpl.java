package com.example.sso.organization.internal.application;

import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link OrganizationAuthorization}: an actor administers an organization iff they hold
 * {@code ROLE_ORG_ADMIN} and are a member of it. Membership-gated on top of the role, so a bare role grants
 * nothing. The organization is the tenant, so there is no higher (customer) scope.
 */
@Service
@RequiredArgsConstructor
class OrganizationAuthorizationImpl implements OrganizationAuthorization {

    private final UserService users;
    private final OrganizationService organizations;

    @Override
    public boolean canManage(UUID actorUserId, UUID orgId) {
        return isOrgAdmin(actorUserId) && organizations.isMember(orgId, actorUserId);
    }

    @Override
    public Set<UUID> scopedOrgIds(UUID actorUserId) {
        return isOrgAdmin(actorUserId) ? organizations.orgIdsForUser(actorUserId) : Set.of();
    }

    private boolean isOrgAdmin(UUID actorUserId) {
        return users.hasRole(actorUserId, Roles.ORG_ADMIN);
    }
}
