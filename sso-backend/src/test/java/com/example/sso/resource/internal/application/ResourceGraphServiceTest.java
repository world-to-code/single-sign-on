package com.example.sso.resource.internal.application;

import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the global cycle guard on edge insertion. The service's job is a sequence of
 * interactions (take the advisory lock, run the reachability check, then mutate the aggregate), so
 * this asserts with {@code verify}: the lock is always taken, and a cycle-forming edge is rejected
 * with a {@link ConflictException} before {@code addChild} runs.
 */
@ExtendWith(MockitoExtension.class)
class ResourceGraphServiceTest {

    @Mock
    private ResourceRepository resources;
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
    void attachChildLocksThenChecksThenAttachesWhenAcyclic() {
        bothResourcesExist();
        when(scope.reaches(childId, parentId)).thenReturn(false);

        graph.attachChild(parentId, childId);

        verify(resources).lockEdgeMutations();
        verify(scope).reaches(childId, parentId);
        verify(parent).addChild(child);
    }

    @Test
    void attachChildRejectsAnEdgeThatWouldCloseACycle() {
        bothResourcesExist();
        when(scope.reaches(childId, parentId)).thenReturn(true);

        assertThatThrownBy(() -> graph.attachChild(parentId, childId))
                .isInstanceOf(ConflictException.class);

        verify(resources).lockEdgeMutations();
        verify(parent, never()).addChild(child);
    }

    @Test
    void attachChildRejectsAMissingParent() {
        when(resources.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> graph.attachChild(parentId, childId))
                .isInstanceOf(NotFoundException.class);
    }
}
