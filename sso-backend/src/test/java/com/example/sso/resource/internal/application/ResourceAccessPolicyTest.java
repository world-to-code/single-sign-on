package com.example.sso.resource.internal.application;

import com.example.sso.resource.ApplicationAuthorization;
import com.example.sso.resource.GroupAuthorization;
import com.example.sso.resource.ResourceAuthorization;
import com.example.sso.resource.UserAuthorization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the subtree-scope (ABAC) gate. A super admin ({@code ROLE_ADMIN} in the security
 * context) is UNSCOPED and bypasses the per-resource ports; a delegated admin is confined to their
 * managed subtree; an unresolvable principal fails closed. Decisions are asserted on the return/throw,
 * with {@code verify(..., never())} to prove the super-admin bypass short-circuits the ports.
 */
@ExtendWith(MockitoExtension.class)
class ResourceAccessPolicyTest {

    @Mock
    private UserService users;
    @Mock
    private ResourceAuthorization resourceAuth;
    @Mock
    private GroupAuthorization groupAuth;
    @Mock
    private ApplicationAuthorization appAuth;
    @Mock
    private UserAuthorization userAuth;
    @Mock
    private OrganizationService organizations;

    @InjectMocks
    private ResourceAccessPolicy policy;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... authorities) {
        List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, granted));
    }

    private UUID authenticateAsResolvedUser(String username, String... authorities) {
        authenticateAs(username, authorities);
        UUID actorId = UUID.randomUUID();
        UserAccount actor = mock(UserAccount.class);
        when(actor.getId()).thenReturn(actorId);
        when(users.findByUsername(username)).thenReturn(Optional.of(actor));
        return actorId;
    }

    @Test
    void adminAuthorityIsUnscoped() {
        authenticateAs("root", Roles.ADMIN);

        assertThat(policy.isUnscoped()).isTrue();
    }

    @Test
    void ordinaryAuthorityIsScoped() {
        authenticateAs("alice", Roles.USER);

        assertThat(policy.isUnscoped()).isFalse();
    }

    @Test
    void superAdminCanManageWithoutConsultingTheResourcePort() {
        authenticateAs("root", Roles.ADMIN);

        assertThat(policy.canManage(UUID.randomUUID())).isTrue();
        verify(resourceAuth, never()).canManage(any(), any());
    }

    @Test
    void scopedCallerDelegatesToTheResourcePort() {
        UUID actorId = authenticateAsResolvedUser("alice", Roles.USER);
        UUID resourceId = UUID.randomUUID();
        when(resourceAuth.canManage(actorId, resourceId)).thenReturn(true);

        assertThat(policy.canManage(resourceId)).isTrue();
    }

    @Test
    void requireManageThrowsForbiddenWhenOutOfScope() {
        UUID actorId = authenticateAsResolvedUser("alice", Roles.USER);
        UUID resourceId = UUID.randomUUID();
        when(resourceAuth.canManage(actorId, resourceId)).thenReturn(false);

        assertThatThrownBy(() -> policy.requireManage(resourceId)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireManageThrowsUnauthorizedWhenNoAuthentication() {
        assertThatThrownBy(() -> policy.requireManage(UUID.randomUUID()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void requireManagesMemberSkipsThePortForASuperAdmin() {
        authenticateAs("root", Roles.ADMIN);

        assertThatCode(() -> policy.requireManagesMember(MemberType.GROUP, UUID.randomUUID().toString()))
                .doesNotThrowAnyException();
        verify(groupAuth, never()).canManage(any(), any());
    }

    @Test
    void requireManagesMemberForbidsAGroupTheScopedCallerDoesNotManage() {
        UUID actorId = authenticateAsResolvedUser("alice", Roles.USER);
        UUID groupId = UUID.randomUUID();
        when(groupAuth.canManage(eq(actorId), eq(groupId))).thenReturn(false);

        assertThatThrownBy(() -> policy.requireManagesMember(MemberType.GROUP, groupId.toString()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireManagesMemberForbidsAResourceKindMember() {
        authenticateAsResolvedUser("alice", Roles.USER);

        assertThatThrownBy(() -> policy.requireManagesMember(MemberType.RESOURCE, "anything"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireGranteeInOrgAllowsAMemberOfTheResourcesOrg() {
        authenticateAs("orgadmin", Roles.ORG_ADMIN);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(organizations.isMember(orgId, userId)).thenReturn(true);

        assertThatCode(() -> policy.requireGranteeInOrg(orgId, userId)).doesNotThrowAnyException();
    }

    @Test
    void requireGranteeInOrgRejectsANonMember() {
        authenticateAs("orgadmin", Roles.ORG_ADMIN);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(organizations.isMember(orgId, userId)).thenReturn(false);

        assertThatThrownBy(() -> policy.requireGranteeInOrg(orgId, userId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireGranteeInOrgSkipsTheMembershipCheckForASuperAdmin() {
        authenticateAs("admin", Roles.ADMIN);

        assertThatCode(() -> policy.requireGranteeInOrg(UUID.randomUUID(), UUID.randomUUID()))
                .doesNotThrowAnyException();
        verify(organizations, never()).isMember(any(), any());
    }

    @Test
    void requireGranteeInOrgSkipsTheMembershipCheckForAGlobalResource() {
        authenticateAs("orgadmin", Roles.ORG_ADMIN);

        assertThatCode(() -> policy.requireGranteeInOrg(null, UUID.randomUUID())).doesNotThrowAnyException();
        verify(organizations, never()).isMember(any(), any());
    }
}
