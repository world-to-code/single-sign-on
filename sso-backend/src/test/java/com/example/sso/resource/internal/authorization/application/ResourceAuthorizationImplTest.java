package com.example.sso.resource.internal.authorization.application;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the resource-scope port. {@code canManage} is unscoped-bypass OR subtree membership;
 * the super-admin bypass must short-circuit before the (potentially expensive) managed-set lookup —
 * asserted with {@code verify(..., never())}.
 */
@ExtendWith(MockitoExtension.class)
class ResourceAuthorizationImplTest {

    @Mock
    private ResourceScope scope;

    @InjectMocks
    private ResourceAuthorizationImpl authorization;

    private final UUID actor = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();

    @Test
    void unscopedActorManagesEverythingWithoutTheManagedSetLookup() {
        when(scope.isUnscoped(actor)).thenReturn(true);

        assertThat(authorization.canManage(actor, resourceId)).isTrue();
        verify(scope, never()).managedResourceIds(actor);
    }

    @Test
    void scopedActorManagesOnlyResourcesInTheirManagedSet() {
        when(scope.isUnscoped(actor)).thenReturn(false);
        when(scope.managedResourceIds(actor)).thenReturn(Set.of(resourceId));

        assertThat(authorization.canManage(actor, resourceId)).isTrue();
        assertThat(authorization.canManage(actor, UUID.randomUUID())).isFalse();
    }

    @Test
    void managedResourceIdsDelegatesToTheScope() {
        Set<UUID> managed = Set.of(resourceId);
        when(scope.managedResourceIds(actor)).thenReturn(managed);

        assertThat(authorization.managedResourceIds(actor)).isEqualTo(managed);
    }
}
