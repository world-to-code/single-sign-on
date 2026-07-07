package com.example.sso.scim.internal.application;

import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.Page;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import de.captaingoldfish.scim.sdk.common.exceptions.ResourceNotFoundException;
import de.captaingoldfish.scim.sdk.common.resources.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the tenant isolation of SCIM user provisioning. Users are GLOBAL identities, so a SCIM
 * token bound to an org (via {@link OrgContext}) must provision INTO that org and only ever see/mutate its
 * own members; a global/platform token keeps the unscoped behaviour. The adversary is a tenant token that
 * reads or tampers with another tenant's user.
 */
@ExtendWith(MockitoExtension.class)
class ScimUserServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private OrgContext orgContext;
    @Mock
    private OrganizationService organizations;

    @InjectMocks
    private ScimUserService service;

    @Test
    void createProvisionsTheUserIntoTheTokenOrg() {
        UUID orgId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        UserAccount created = userAccount(newId);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(userService.existsByUsernameInOrg(eq("scim-u"), any())).thenReturn(false);
        when(userService.createUser(any(), any())).thenReturn(created);
        when(userService.findById(newId)).thenReturn(Optional.of(created));

        service.create(User.builder().userName("scim-u").build());

        verify(organizations).addMember(orgId, newId);
    }

    @Test
    void createWithAGlobalTokenLeavesTheUserUnattached() {
        UUID newId = UUID.randomUUID();
        UserAccount created = userAccount(newId);
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(userService.existsByUsernameInOrg(eq("scim-u"), any())).thenReturn(false);
        when(userService.createUser(any(), any())).thenReturn(created);
        when(userService.findById(newId)).thenReturn(Optional.of(created));

        service.create(User.builder().userName("scim-u").build());

        verify(organizations, never()).addMember(any(), any());
    }

    @Test
    void getRejectsAUserOutsideTheTokenOrg() {
        UUID orgId = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(organizations.isMember(orgId, foreign)).thenReturn(false);

        assertThatThrownBy(() -> service.get(foreign.toString())).isInstanceOf(ResourceNotFoundException.class);
        verify(userService, never()).findById(foreign);
    }

    @Test
    void getReturnsAMemberOfTheTokenOrg() {
        UUID orgId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UserAccount memberAccount = userAccount(member);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(organizations.isMember(orgId, member)).thenReturn(true);
        when(userService.findById(member)).thenReturn(Optional.of(memberAccount));

        assertThat(service.get(member.toString()).getId()).contains(member.toString());
    }

    @Test
    void deleteDeprovisionsMembershipForATenantTokenWithoutDeletingTheIdentity() {
        UUID orgId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(organizations.isMember(orgId, member)).thenReturn(true);

        service.delete(member.toString());

        verify(organizations).removeMember(orgId, member);
        verify(userService, never()).delete(any());
    }

    @Test
    void deleteRemovesTheGlobalIdentityForAGlobalToken() {
        UUID id = UUID.randomUUID();
        UserAccount user = userAccount(id);
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(userService.findById(id)).thenReturn(Optional.of(user));

        service.delete(id.toString());

        verify(userService).delete(id);
        verify(organizations, never()).removeMember(any(), any());
    }

    @Test
    void listScopesToTheTokenOrgsMembers() {
        UUID orgId = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        Set<UUID> members = Set.of(m1, m2);
        UserAccount u1 = userAccount(m1);
        UserAccount u2 = userAccount(m2);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(organizations.memberIds(orgId)).thenReturn(members);
        when(userService.findByIds(eq(members), eq(0), eq(50)))
                .thenReturn(new Page<>(2, 0, 50, List.of(u1, u2)));

        var response = service.list(1, 50);

        assertThat(response.getTotalResults()).isEqualTo(2);
        assertThat(response.getResources()).hasSize(2);
        // the unscoped global page/count path must NOT be used for a tenant token
        verify(userService, never()).count();
    }

    @Test
    void listWithCountZeroReturnsOnlyTheTotalWithoutDividingByZero() {
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(organizations.memberIds(orgId)).thenReturn(Set.of(UUID.randomUUID(), UUID.randomUUID()));

        var response = service.list(1, 0);

        assertThat(response.getTotalResults()).isEqualTo(2);
        assertThat(response.getResources()).isEmpty();
        verify(userService, never()).findByIds(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private UserAccount userAccount(UUID id) {
        UserAccount user = org.mockito.Mockito.mock(UserAccount.class);
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.getUsername()).thenReturn("user-" + id);
        lenient().when(user.getEmail()).thenReturn(id + "@example.com");
        lenient().when(user.isEnabled()).thenReturn(true);
        lenient().when(user.getCreatedAt()).thenReturn(Instant.EPOCH);
        lenient().when(user.getUpdatedAt()).thenReturn(Instant.EPOCH);
        lenient().when(user.getRoles()).thenReturn(Set.of());
        return user;
    }
}
