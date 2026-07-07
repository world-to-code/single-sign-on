package com.example.sso.organization.internal.application;

import com.example.sso.organization.OrganizationService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrganizationAuthorizationImpl}: an actor administers an org iff they hold
 * ROLE_ORG_ADMIN and are a member of it; a non-org-admin has no org scope.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationAuthorizationImplTest {

    @Mock private UserService users;
    @Mock private OrganizationService organizations;

    @InjectMocks private OrganizationAuthorizationImpl authorization;

    private final UUID actor = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();

    @Test
    void anOrgAdminMemberCanManageTheOrg() {
        when(users.hasRole(actor, Roles.ORG_ADMIN)).thenReturn(true);
        when(organizations.isMember(org, actor)).thenReturn(true);

        assertThat(authorization.canManage(actor, org)).isTrue();
    }

    @Test
    void anOrgAdminWhoIsNotAMemberCannotManageTheOrg() {
        when(users.hasRole(actor, Roles.ORG_ADMIN)).thenReturn(true);
        when(organizations.isMember(org, actor)).thenReturn(false);

        assertThat(authorization.canManage(actor, org)).isFalse();
    }

    @Test
    void aNonOrgAdminCannotManageAnyOrg() {
        when(users.hasRole(actor, Roles.ORG_ADMIN)).thenReturn(false);

        assertThat(authorization.canManage(actor, org)).isFalse();
    }

    @Test
    void scopedOrgIdsAreTheOrgAdminsMemberships() {
        Set<UUID> memberships = Set.of(org, UUID.randomUUID());
        when(users.hasRole(actor, Roles.ORG_ADMIN)).thenReturn(true);
        when(organizations.orgIdsForUser(actor)).thenReturn(memberships);

        assertThat(authorization.scopedOrgIds(actor)).isEqualTo(memberships);
    }

    @Test
    void aNonOrgAdminHasNoOrgScope() {
        when(users.hasRole(actor, Roles.ORG_ADMIN)).thenReturn(false);

        assertThat(authorization.scopedOrgIds(actor)).isEmpty();
    }
}
