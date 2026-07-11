package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
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
 * Unit test for the group-scope port: a group is in scope when it is a GROUP member of a managed
 * resource. An empty managed set must short-circuit BEFORE hitting the repository (an {@code IN ()}
 * with no ids is both wasteful and a SQL hazard) — asserted with {@code verify(..., never())}.
 */
@ExtendWith(MockitoExtension.class)
class GroupAuthorizationImplTest {

    @Mock
    private ResourceScope scope;
    @Mock
    private ResourceRepository resources;

    @InjectMocks
    private GroupAuthorizationImpl authorization;

    private final UUID actor = UUID.randomUUID();

    @Test
    void emptyManagedSetShortCircuitsWithoutQueryingMembers() {
        when(scope.managedResourceIds(actor)).thenReturn(Set.of());

        assertThat(authorization.scopedGroupIds(actor)).isEmpty();
        verify(resources, never()).findMemberIds(any(), anyString());
    }

    @Test
    void scopedGroupIdsAreLoadedFromTheManagedResources() {
        UUID managedResource = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(scope.managedResourceIds(actor)).thenReturn(Set.of(managedResource));
        when(resources.findMemberIds(Set.of(managedResource), MemberType.GROUP.name()))
                .thenReturn(Set.of(groupId.toString()));

        assertThat(authorization.scopedGroupIds(actor)).containsExactly(groupId);
    }

    @Test
    void unscopedActorManagesAnyGroupWithoutTheMemberLookup() {
        when(scope.isUnscoped(actor)).thenReturn(true);

        assertThat(authorization.canManage(actor, UUID.randomUUID())).isTrue();
        verify(resources, never()).findMemberIds(any(), anyString());
    }
}
