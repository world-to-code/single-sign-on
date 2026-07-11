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
 * Unit test for the application-scope port: an app is in scope when its id is an APPLICATION member of
 * a managed resource. The empty managed set must short-circuit before the repository call — asserted
 * with {@code verify(..., never())}.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationAuthorizationImplTest {

    @Mock
    private ResourceScope scope;
    @Mock
    private ResourceRepository resources;

    @InjectMocks
    private ApplicationAuthorizationImpl authorization;

    private final UUID actor = UUID.randomUUID();

    @Test
    void emptyManagedSetShortCircuitsWithoutQueryingMembers() {
        when(scope.managedResourceIds(actor)).thenReturn(Set.of());

        assertThat(authorization.scopedAppIds(actor)).isEmpty();
        verify(resources, never()).findMemberIds(any(), anyString());
    }

    @Test
    void scopedAppIdsAreLoadedFromTheManagedResources() {
        UUID managedResource = UUID.randomUUID();
        when(scope.managedResourceIds(actor)).thenReturn(Set.of(managedResource));
        when(resources.findMemberIds(Set.of(managedResource), MemberType.APPLICATION.name()))
                .thenReturn(Set.of("shop-client"));

        assertThat(authorization.scopedAppIds(actor)).containsExactly("shop-client");
    }

    @Test
    void unscopedActorManagesAnyAppWithoutTheMemberLookup() {
        when(scope.isUnscoped(actor)).thenReturn(true);

        assertThat(authorization.canManage(actor, "any-client")).isTrue();
        verify(resources, never()).findMemberIds(any(), anyString());
    }
}
