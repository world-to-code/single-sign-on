package com.example.sso.resource.internal.application;

import com.example.sso.portal.ApplicationService;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceEdgeRepository;
import com.example.sso.resource.internal.domain.ResourceGrantRowRepository;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the resource-admin service's SCOPE enforcement and view assembly. Where the unit's
 * job is an interaction (the edge guard checks BOTH endpoints; sub-resource creation checks only the
 * parent; detail avoids the app registry when there are no app members) it asserts with {@code verify}.
 * The explicit edge/member/grant repos are stubbed empty so the view projection is exercised.
 */
@ExtendWith(MockitoExtension.class)
class ResourceAdminServiceTest {

    @Mock
    private ResourceRepository resources;
    @Mock
    private ResourceTypeRepository types;
    @Mock
    private ResourceTypeAllowedMemberRepository allowedMembers;
    @Mock
    private ResourceEdgeRepository edges;
    @Mock
    private ResourceMemberRowRepository memberRows;
    @Mock
    private ResourceGrantRowRepository grantRows;
    @Mock
    private ResourceGraphService graph;
    @Mock
    private ResourceAccessPolicy access;
    @Mock
    private UserService users;
    @Mock
    private UserGroupService groups;
    @Mock
    private ApplicationService applications;

    @InjectMocks
    private ResourceAdminService service;

    @Test
    void deleteTypeRejectsATypeStillInUse() {
        UUID typeId = UUID.randomUUID();
        when(types.findById(typeId)).thenReturn(Optional.of(mock(ResourceType.class)));
        when(resources.existsByTypeId(typeId)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteType(typeId)).isInstanceOf(ConflictException.class);
        verify(types, never()).delete(any());
        verify(allowedMembers, never()).deleteByTypeId(any());
    }

    @Test
    void deleteTypeRemovesAnUnusedTypeAndItsMemberKinds() {
        UUID typeId = UUID.randomUUID();
        ResourceType type = mock(ResourceType.class);
        when(types.findById(typeId)).thenReturn(Optional.of(type));
        when(resources.existsByTypeId(typeId)).thenReturn(false);

        service.deleteType(typeId);

        verify(allowedMembers).deleteByTypeId(typeId);
        verify(types).delete(type);
    }

    @Test
    void deleteTypeUnknownIsNotFound() {
        UUID typeId = UUID.randomUUID();
        when(types.findById(typeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteType(typeId)).isInstanceOf(NotFoundException.class);
    }

    /** Stubs a mock Resource plus its empty edge/member/grant rows so a single view can be projected. */
    private Resource viewable(UUID id) {
        Resource resource = mock(Resource.class);
        ResourceType type = mock(ResourceType.class);
        lenient().when(type.getName()).thenReturn("TEAM");
        lenient().when(resource.getId()).thenReturn(id);
        lenient().when(resource.getName()).thenReturn("node-" + id);
        lenient().when(resource.getType()).thenReturn(type);
        lenient().when(resources.findByIdFetchingType(id)).thenReturn(Optional.of(resource));
        lenient().when(edges.findByParentId(id)).thenReturn(List.of());
        lenient().when(memberRows.findByResourceId(id)).thenReturn(List.of());
        lenient().when(grantRows.findByResourceId(id)).thenReturn(List.of());
        lenient().when(resources.findAllById(anyCollection())).thenReturn(List.of());
        return resource;
    }

    @Test
    void attachChildRequiresManagingBothEndpointsBeforeWiringTheEdge() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        service.attachChild(parentId, childId);

        verify(access).requireManage(parentId);
        verify(access).requireManage(childId);
        verify(graph).attachChild(parentId, childId);
    }

    @Test
    void createSubResourceRequiresManagingOnlyTheParent() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Resource child = viewable(childId);
        when(resources.findById(parentId)).thenReturn(Optional.of(mock(Resource.class))); // require(parentId) for its org
        when(types.findByName("TEAM")).thenReturn(Optional.of(mock(ResourceType.class)));
        when(resources.save(any(Resource.class))).thenReturn(child);

        service.createSubResource(parentId, "Sub", "TEAM");

        verify(access).requireManage(parentId);
        verify(access, times(1)).requireManage(any());  // the child endpoint is NOT scope-checked
        verify(graph).attachChild(parentId, childId);
    }

    @Test
    void detailDoesNotQueryTheAppRegistryWhenThereAreNoApplicationMembers() {
        UUID id = UUID.randomUUID();
        viewable(id); // build (and stub) the mock BEFORE the outer when(...)
        when(access.isUnscoped()).thenReturn(true);
        when(edges.findByChildId(id)).thenReturn(List.of());
        when(groups.idNames(anySet())).thenReturn(List.of());
        when(users.idNames(anySet())).thenReturn(List.of());

        service.detail(id);

        verify(applications, never()).listApplications();
    }

    @Test
    void listReturnsOnlyResourcesInsideTheScopedCallersManagedSet() {
        UUID managedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Resource managed = viewable(managedId);
        Resource other = viewable(otherId);
        when(access.isUnscoped()).thenReturn(false);
        when(access.managedResourceIds()).thenReturn(Set.of(managedId));
        when(resources.findAllFetchingType()).thenReturn(List.of(managed, other));
        when(edges.findByParentIdIn(anyCollection())).thenReturn(List.of());
        when(memberRows.findByResourceIdIn(anyCollection())).thenReturn(List.of());
        when(grantRows.findByResourceIdIn(anyCollection())).thenReturn(List.of());

        List<ResourceView> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(managedId.toString());
    }
}
