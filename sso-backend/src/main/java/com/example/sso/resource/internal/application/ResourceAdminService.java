package com.example.sso.resource.internal.application;

import com.example.sso.portal.ApplicationService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceRoleTier;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management of the resource DAG: types, nodes, edges, members, and delegation grants. Every
 * referenced entity (member group/user/application, grantee) is validated to EXIST before it is
 * attached, so scope rows can never point at ids that were never real. Views are projected inside
 * the loading transaction (the collections are LAZY).
 *
 * <p>Enforcement is layered: the controller's {@code @RequirePermission} gates PBAC, then THIS service
 * enforces subtree SCOPE per method through {@link ResourceAccessPolicy} (a super admin bypasses; a
 * delegated resource admin is confined to their subtree). Scope is checked imperatively here rather
 * than via a {@code @Can*}/{@code @PreAuthorize} annotation because {@code list()} must FILTER results
 * and the edge/pull-in guards read multiple arguments — so all resource-scope logic stays co-located.
 */
@Service
@RequiredArgsConstructor
public class ResourceAdminService {

    private final ResourceRepository resources;
    private final ResourceTypeRepository types;
    private final ResourceGraphService graph;
    private final ResourceAccessPolicy access;
    private final UserService users;
    private final UserGroupService groups;
    private final ApplicationService applications;

    // --- Types ---

    @Transactional(readOnly = true)
    public List<ResourceTypeView> listTypes() {
        return types.findAllWithKinds().stream().map(ResourceTypeView::of).toList();
    }

    // create/createType are intentionally unscoped: they mint a DETACHED node/type with no grants or
    // edges, conferring no reach — a scoped admin can only wire it in later via the (both-endpoints) edge guard.
    @Transactional
    public ResourceTypeView createType(String name, Set<MemberType> allowedMemberTypes) {
        if (types.findByNameFetchingKinds(name).isPresent()) {
            throw new ConflictException("A resource type with this name already exists.");
        }
        return ResourceTypeView.of(types.save(new ResourceType(name, allowedMemberTypes)));
    }

    // --- Resources ---

    @Transactional(readOnly = true)
    public List<ResourceView> list() {
        boolean unscoped = access.isUnscoped();
        Set<UUID> managed = unscoped ? Set.of() : access.managedResourceIds();
        return resources.findAllForAdminView().stream()
                .filter(resource -> unscoped || managed.contains(resource.getId()))
                .map(ResourceView::of).toList();
    }

    @Transactional(readOnly = true)
    public ResourceView get(UUID id) {
        access.requireManage(id);
        return ResourceView.of(requireForView(id));
    }

    @Transactional
    public ResourceView create(String name, String typeName) {
        ResourceType type = types.findByNameFetchingKinds(typeName)
                .orElseThrow(() -> new NotFoundException("Resource type not found."));
        Resource saved = resources.save(new Resource(name, type));
        return ResourceView.of(saved);
    }

    /**
     * Creates a sub-resource UNDER a parent the caller manages, and attaches it — the only way a scoped
     * (non-super) admin grows their subtree. Requires managing the PARENT only: the child is a fresh node
     * the caller creates (no existing reach to smuggle), and being a descendant of the managed parent it
     * lands inside the caller's scope. Cycle-safe (a brand-new node has no edges).
     */
    @Transactional
    public ResourceView createSubResource(UUID parentId, String name, String typeName) {
        access.requireManage(parentId);
        ResourceType type = types.findByNameFetchingKinds(typeName)
                .orElseThrow(() -> new NotFoundException("Resource type not found."));
        Resource child = resources.save(new Resource(name, type));
        graph.attachChild(parentId, child.getId());
        return ResourceView.of(requireForView(child.getId()));
    }

    @Transactional
    public ResourceView rename(UUID id, String name) {
        access.requireManage(id);
        Resource resource = requireForView(id);
        resource.rename(name);
        return ResourceView.of(resource);
    }

    @Transactional
    public void delete(UUID id) {
        access.requireManage(id);
        resources.delete(require(id)); // edges/members/grants go with it (DB cascade)
    }

    // --- Edges (cycle-checked + serialized in ResourceGraphService) ---

    @Transactional
    public void attachChild(UUID parentId, UUID childId) {
        // Edge guard: must manage BOTH endpoints, or a scoped admin could graft an unmanaged subtree
        // under theirs (to absorb it) or attach their subtree under a parent to inherit upward. One tx
        // gives the scope check and the graph write a consistent snapshot (matches the other mutators, OSIV-off).
        access.requireManage(parentId);
        access.requireManage(childId);
        graph.attachChild(parentId, childId);
    }

    @Transactional
    public void detachChild(UUID parentId, UUID childId) {
        access.requireManage(parentId);
        graph.detachChild(parentId, childId);
    }

    // --- Members ---

    @Transactional
    public ResourceView attachMember(UUID id, MemberType memberType, String memberId) {
        access.requireManage(id);
        access.requireManagesMember(memberType, memberId); // pull-in guard
        Resource resource = resources.findByIdWithTypeKinds(id)
                .orElseThrow(() -> new NotFoundException("Resource not found."));
        requireMemberExists(memberType, memberId);
        resource.attachMember(new ResourceMember(memberType, memberId));
        return ResourceView.of(requireForView(id));
    }

    @Transactional
    public ResourceView detachMember(UUID id, MemberType memberType, String memberId) {
        access.requireManage(id);
        Resource resource = require(id);
        resource.detachMember(member(memberType, memberId));
        return ResourceView.of(requireForView(id));
    }

    /** Builds a member value, turning a malformed GROUP/USER uuid into a 400 rather than a 500. */
    private ResourceMember member(MemberType memberType, String memberId) {
        if (memberType == MemberType.GROUP || memberType == MemberType.USER) {
            MemberIds.requireUuid(memberId);
        }
        return new ResourceMember(memberType, memberId);
    }

    // --- Delegation grants ---

    @Transactional
    public ResourceView assignAdmin(UUID id, UUID userId) {
        access.requireManage(id); // delegate admin only WITHIN your managed subtree
        users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        Resource resource = require(id);
        resource.grant(ResourceGrant.admin(userId));
        return ResourceView.of(requireForView(id));
    }

    @Transactional
    public ResourceView revokeAdmin(UUID id, UUID userId) {
        access.requireManage(id);
        Resource resource = require(id);
        resource.revoke(userId, ResourceRoleTier.ADMIN);
        return ResourceView.of(requireForView(id));
    }

    private Resource require(UUID id) {
        return resources.findById(id).orElseThrow(() -> new NotFoundException("Resource not found."));
    }

    private Resource requireForView(UUID id) {
        return resources.findByIdForAdminView(id).orElseThrow(() -> new NotFoundException("Resource not found."));
    }

    /** The referenced member must actually exist in its own directory/registry. */
    private void requireMemberExists(MemberType memberType, String memberId) {
        boolean exists = switch (memberType) {
            case GROUP -> {
                groups.get(MemberIds.requireUuid(memberId)); // throws NotFoundException when absent
                yield true;
            }
            case USER -> users.findById(MemberIds.requireUuid(memberId)).isPresent();
            case APPLICATION -> applications.listApplications().stream()
                    .anyMatch(app -> app.id().equals(memberId));
            case RESOURCE -> throw new BadRequestException("Child resources are attached as edges, not members.");
        };
        if (!exists) {
            throw new NotFoundException("Member " + memberType + " not found.");
        }
    }
}
