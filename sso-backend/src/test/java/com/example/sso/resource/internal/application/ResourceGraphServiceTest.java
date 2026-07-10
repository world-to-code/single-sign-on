package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceEdge;
import com.example.sso.resource.internal.domain.ResourceEdgeRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the global cycle guard on edge insertion. The service's job is a sequence of
 * interactions (take the advisory lock, run the reachability check, then insert the explicit edge
 * row), so this asserts with {@code verify}: the lock is always taken, and a cycle-forming edge is
 * rejected with a {@link ConflictException} before any {@link ResourceEdge} is saved.
 */
@ExtendWith(MockitoExtension.class)
class ResourceGraphServiceTest {

    @Mock
    private ResourceRepository resources;
    @Mock
    private ResourceEdgeRepository edges;
    @Mock
    private ResourceTypeAllowedMemberRepository allowedMembers;
    @Mock
    private ResourceScope scope;

    @InjectMocks
    private ResourceGraphService graph;

    private final UUID parentId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();

    @Mock
    private Resource parent;
    @Mock
    private Resource child;

    private void bothResourcesExist() {
        when(resources.findById(parentId)).thenReturn(Optional.of(parent));
        when(resources.findById(childId)).thenReturn(Optional.of(child));
    }

    @Test
    void attachChildLocksThenChecksThenInsertsWhenAcyclic() {
        bothResourcesExist();
        when(scope.reaches(childId, parentId)).thenReturn(false);
        ResourceType type = new ResourceType("TEAM", null);
        lenient().when(parent.getType()).thenReturn(type);
        lenient().when(allowedMembers.findByTypeId(any())).thenReturn(List.of());

        graph.attachChild(parentId, childId);

        verify(resources).lockEdgeMutations();
        verify(scope).reaches(childId, parentId);
        verify(edges).save(any(ResourceEdge.class));
    }

    @Test
    void attachChildRejectsAnEdgeThatWouldCloseACycle() {
        bothResourcesExist();
        when(scope.reaches(childId, parentId)).thenReturn(true);

        assertThatThrownBy(() -> graph.attachChild(parentId, childId))
                .isInstanceOf(ConflictException.class);

        verify(resources).lockEdgeMutations();
        verify(edges, never()).save(any());
    }

    @Test
    void attachChildRejectsACrossOrgEdge() {
        // Single-org invariant: an edge may not bridge two tenants (nor global↔tenant). The graph service
        // guarantees this itself — a domain invariant, not merely the admin-layer tier gate — and rejects
        // before the reachability query, as a clean 4xx rather than an RLS WITH CHECK 500.
        bothResourcesExist();
        when(parent.getOrgId()).thenReturn(UUID.randomUUID());
        when(child.getOrgId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> graph.attachChild(parentId, childId))
                .isInstanceOf(BadRequestException.class);

        verify(scope, never()).reaches(any(), any());
        verify(edges, never()).save(any());
    }

    @Test
    void attachChildRejectsAGlobalToTenantBridge() {
        // A global (org null) node linked under a tenant node (or vice versa) is a bridge — also rejected.
        bothResourcesExist();
        when(parent.getOrgId()).thenReturn(null);
        when(child.getOrgId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> graph.attachChild(parentId, childId))
                .isInstanceOf(BadRequestException.class);

        verify(edges, never()).save(any());
    }

    @Test
    void attachChildRejectsAMissingParent() {
        when(resources.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> graph.attachChild(parentId, childId))
                .isInstanceOf(NotFoundException.class);
    }
}
