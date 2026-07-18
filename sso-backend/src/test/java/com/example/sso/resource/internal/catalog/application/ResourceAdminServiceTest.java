package com.example.sso.resource.internal.catalog.application;

import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.resource.internal.authorization.application.ResourceAccessPolicy;
import com.example.sso.resource.internal.graph.application.ResourceGraphService;

import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceEdgeRepository;
import com.example.sso.resource.internal.domain.ResourceGrantRowRepository;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import org.springframework.context.ApplicationEventPublisher;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private AttributeService attributes;
    @Mock
    private UserService users;
    @Mock
    private UserGroupService groups;
    @Mock
    private ApplicationService applications;
    @Mock
    private OrgContext orgContext;

    private ResourceAdminService service;

    @BeforeEach
    void buildService() {
        // Exercise the REAL tier guard (driven by the mocked OrgContext) so the org-tier isolation checks are
        // genuine; an unbound context (currentOrg empty) means the caller's tier is the GLOBAL tier (null),
        // matching the mock resources' default null org so the happy-path loads pass through.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        // deleteType checks type usage RLS-blind via callAsPlatform; run the supplier inline in the mock.
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(invocation -> invocation.getArgument(0, Supplier.class).get());
        service = new ResourceAdminService(resources, types, allowedMembers, edges, memberRows, grantRows,
                graph, access, attributes, users, groups, applications, new OrgTierGuard(orgContext), orgContext,
                mock(ApplicationEventPublisher.class));
    }

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
        when(resources.findById(parentId)).thenReturn(Optional.of(mock(Resource.class))); // tier-check both endpoints
        when(resources.findById(childId)).thenReturn(Optional.of(mock(Resource.class)));

        service.attachChild(parentId, childId);

        verify(access).requireManage(parentId);
        verify(access).requireManage(childId);
        verify(graph).attachChild(parentId, childId);
    }

    @Test
    void addAttributeRequiresManagingTheResourceThenDelegatesToTheMetadataStore() {
        UUID id = UUID.randomUUID();
        when(resources.findById(id)).thenReturn(Optional.of(mock(Resource.class))); // in the (global) tier
        when(attributes.attributesOf(EntityKind.RESOURCE, id.toString())).thenReturn(List.of());

        service.addAttribute(id, "dept", "eng");

        verify(access).requireManage(id); // scope-gated before the write
        verify(attributes).add(EntityKind.RESOURCE, id.toString(), "dept", "eng");
    }

    @Test
    void addAttributeRejectsAForeignTierResourceBeforeWriting() {
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID())); // acting in some tenant
        Resource foreign = mock(Resource.class);
        when(foreign.getOrgId()).thenReturn(UUID.randomUUID()); // a resource in a DIFFERENT org
        when(resources.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.addAttribute(id, "dept", "eng")).isInstanceOf(NotFoundException.class);
        verify(attributes, never()).add(any(), any(), any(), any());
    }

    @Test
    void removeAttributeRejectsAForeignTierResourceBeforeDeleting() {
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));
        Resource foreign = mock(Resource.class);
        when(foreign.getOrgId()).thenReturn(UUID.randomUUID());
        when(resources.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.removeAttribute(id, "dept")).isInstanceOf(NotFoundException.class);
        verify(attributes, never()).remove(any(), any(), any());
    }

    @Test
    void removeAttributeValueRejectsAForeignTierResourceBeforeDeleting() {
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));
        Resource foreign = mock(Resource.class);
        when(foreign.getOrgId()).thenReturn(UUID.randomUUID());
        when(resources.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.removeAttributeValue(id, "dept", "eng")).isInstanceOf(NotFoundException.class);
        verify(attributes, never()).removeValue(any(), any(), any(), any());
    }

    @Test
    void createSubResourceRequiresManagingOnlyTheParent() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Resource child = viewable(childId);
        when(resources.findById(parentId)).thenReturn(Optional.of(mock(Resource.class))); // tier-checked parent load for its org
        when(types.findByNameAndOrgIdIsNull("TEAM")).thenReturn(Optional.of(mock(ResourceType.class)));
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
        when(access.isTierAdmin()).thenReturn(true);
        when(edges.findByChildId(id)).thenReturn(List.of());
        when(groups.idNames(anySet())).thenReturn(List.of());
        when(users.idNames(anySet())).thenReturn(List.of());

        service.detail(id);

        verify(applications, never()).listApplications();
    }

    @Test
    void listReturnsOnlyResourcesInsideTheScopedCallersViewableSet() {
        // A resource delegate's list is their VIEWABLE set (ADMIN or VIEWER subtrees) — so a pure VIEWER can see
        // the tree they were granted read access to, not just what they manage.
        UUID viewableId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Resource inScope = viewable(viewableId);
        Resource other = viewable(otherId);
        when(access.isTierAdmin()).thenReturn(false); // a mere resource delegate — not a tenant/super admin
        when(access.viewableResourceIds()).thenReturn(Set.of(viewableId));
        when(resources.findAllFetchingType()).thenReturn(List.of(inScope, other));
        when(edges.findByParentIdIn(anyCollection())).thenReturn(List.of());
        when(memberRows.findByResourceIdIn(anyCollection())).thenReturn(List.of());
        when(grantRows.findByResourceIdIn(anyCollection())).thenReturn(List.of());

        List<ResourceView> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(viewableId.toString());
    }

    @Test
    void aTierAdminSeesTheWholeDirectoryRlsReturnsNotJustASubtree() {
        // A tenant admin (or super) is a tier admin: they see every resource RLS returns — their org's whole
        // tree — WITHOUT being narrowed to a managed subtree (which is empty for a pure tenant admin).
        Resource one = viewable(UUID.randomUUID());
        Resource two = viewable(UUID.randomUUID());
        when(access.isTierAdmin()).thenReturn(true);
        when(resources.findAllFetchingType()).thenReturn(List.of(one, two));
        when(edges.findByParentIdIn(anyCollection())).thenReturn(List.of());
        when(memberRows.findByResourceIdIn(anyCollection())).thenReturn(List.of());
        when(grantRows.findByResourceIdIn(anyCollection())).thenReturn(List.of());

        assertThat(service.list()).hasSize(2);
        verify(access, never()).viewableResourceIds(); // never narrowed to a subtree (list uses the tier filter)
    }

    @Test
    void attachMemberRejectsAUserFromADifferentOrg() {
        UUID resourceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        viewable(resourceId); // a GLOBAL resource (org null)
        UserAccount user = mock(UserAccount.class);
        when(user.getOrgId()).thenReturn(UUID.randomUUID()); // the user belongs to a tenant
        when(users.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.USER, userId.toString()))
                .isInstanceOf(BadRequestException.class);
        verify(memberRows, never()).save(any());
    }

    @Test
    void attachMemberRejectsAGroupFromADifferentOrg() {
        UUID resourceId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        viewable(resourceId); // a GLOBAL resource (org null)
        when(groups.orgIdOf(groupId)).thenReturn(Optional.of(UUID.randomUUID())); // the group belongs to a tenant

        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.GROUP, groupId.toString()))
                .isInstanceOf(BadRequestException.class);
        verify(memberRows, never()).save(any());
    }

    @Test
    void attachMemberRejectsACrossTenantUserWhenDrilledIntoAnOrg() {
        UUID orgA = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA)); // caller is drilled into org A
        Resource resource = viewable(resourceId);
        when(resource.getOrgId()).thenReturn(orgA); // the resource lives in org A
        UserAccount user = mock(UserAccount.class);
        when(user.getOrgId()).thenReturn(UUID.randomUUID()); // the user lives in a different org
        when(users.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.USER, userId.toString()))
                .isInstanceOf(BadRequestException.class);
        verify(memberRows, never()).save(any());
    }

    @Test
    void attachMemberAttachesASameOrgUser() {
        UUID resourceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        viewable(resourceId); // a GLOBAL resource (org null)
        UserAccount user = mock(UserAccount.class);
        when(user.getOrgId()).thenReturn(null); // a global user matches the global resource
        when(users.findById(userId)).thenReturn(Optional.of(user));

        service.attachMember(resourceId, MemberType.USER, userId.toString());

        verify(memberRows).save(any());
    }

    @Test
    void attachMemberAttachesAnApplicationWithoutAnOrgCheck() {
        UUID resourceId = UUID.randomUUID();
        String appId = "oidc:demo-client";
        viewable(resourceId);
        ApplicationView app = mock(ApplicationView.class);
        when(app.id()).thenReturn(appId);
        when(applications.listApplications()).thenReturn(List.of(app));

        service.attachMember(resourceId, MemberType.APPLICATION, appId);

        verify(memberRows).save(any());
        verify(users, never()).findById(any());
        verify(groups, never()).orgIdOf(any());
    }

    @Test
    void attachMemberRejectsASystemAppOnATenantResource() {
        // The global admin-console / user-portal app is republished into every tenant's catalog but is org-less:
        // a tenant admin must not pull it into an org-scoped resource, or a delegated sub-admin under that
        // resource would gain management reach over a platform app.
        UUID orgA = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        String appId = "oidc:admin-console";
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        Resource resource = viewable(resourceId);
        when(resource.getOrgId()).thenReturn(orgA); // a tenant (org-scoped) resource
        ApplicationView app = mock(ApplicationView.class);
        when(app.id()).thenReturn(appId);
        when(app.system()).thenReturn(true); // a platform-managed global app
        when(applications.listApplications()).thenReturn(List.of(app));

        assertThatThrownBy(() -> service.attachMember(resourceId, MemberType.APPLICATION, appId))
                .isInstanceOf(BadRequestException.class);
        verify(memberRows, never()).save(any());
    }
}
