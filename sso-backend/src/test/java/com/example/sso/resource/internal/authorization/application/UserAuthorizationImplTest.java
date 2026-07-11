package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.authorization.GroupAuthorization;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.user.UserGroupService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the user-scope port: a user is in scope when they are a direct USER member of a managed
 * resource, or share a group with one. An empty managed set (and empty scoped groups) must fail closed
 * WITHOUT touching the member repository or the group directory — asserted with {@code verify(never())}.
 */
@ExtendWith(MockitoExtension.class)
class UserAuthorizationImplTest {

    @Mock
    private ResourceScope scope;
    @Mock
    private GroupAuthorization groups;
    @Mock
    private ResourceRepository resources;
    @Mock
    private UserGroupService userGroups;

    @InjectMocks
    private UserAuthorizationImpl authorization;

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void unscopedActorManagesAnyUser() {
        when(scope.isUnscoped(actor)).thenReturn(true);

        assertThat(authorization.canManage(actor, target)).isTrue();
        verify(resources, never()).findMemberIds(any(), anyString());
    }

    @Test
    void scopedActorWithNoManagedResourcesNorGroupsFailsClosed() {
        when(scope.isUnscoped(actor)).thenReturn(false);
        when(scope.managedResourceIds(actor)).thenReturn(Set.of());
        when(groups.scopedGroupIds(actor)).thenReturn(Set.of());

        assertThat(authorization.canManage(actor, target)).isFalse();
        verify(resources, never()).findMemberIds(any(), anyString());
        verify(userGroups, never()).groupIdsOf(any());
    }

    @Test
    void aDirectUserMemberOfAManagedResourceIsInScope() {
        UUID managedResource = UUID.randomUUID();
        when(scope.isUnscoped(actor)).thenReturn(false);
        when(scope.managedResourceIds(actor)).thenReturn(Set.of(managedResource));
        when(resources.findMemberIds(Set.of(managedResource), MemberType.USER.name()))
                .thenReturn(Set.of(target.toString()));

        assertThat(authorization.canManage(actor, target)).isTrue();
    }
}
