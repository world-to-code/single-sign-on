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
 * the loading transaction (the collections are LAZY). Scope ENFORCEMENT (who may call this) stays
 * with {@code @RequirePermission} at the controller until the subtree-scoping phase.
 */
@Service
@RequiredArgsConstructor
public class ResourceAdminService {

    private final ResourceRepository resources;
    private final ResourceTypeRepository types;
    private final ResourceGraphService graph;
    private final UserService users;
    private final UserGroupService groups;
    private final ApplicationService applications;

    // --- Types ---

    @Transactional(readOnly = true)
    public List<ResourceTypeView> listTypes() {
        return types.findAllWithKinds().stream().map(ResourceTypeView::of).toList();
    }

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
        return resources.findAllForAdminView().stream().map(ResourceView::of).toList();
    }

    @Transactional(readOnly = true)
    public ResourceView get(UUID id) {
        return ResourceView.of(requireForView(id));
    }

    @Transactional
    public ResourceView create(String name, String typeName) {
        ResourceType type = types.findByNameFetchingKinds(typeName)
                .orElseThrow(() -> new NotFoundException("Resource type not found."));
        Resource saved = resources.save(new Resource(name, type));
        return ResourceView.of(saved);
    }

    @Transactional
    public ResourceView rename(UUID id, String name) {
        Resource resource = requireForView(id);
        resource.rename(name);
        return ResourceView.of(resource);
    }

    @Transactional
    public void delete(UUID id) {
        resources.delete(require(id)); // edges/members/grants go with it (DB cascade)
    }

    // --- Edges (cycle-checked + serialized in ResourceGraphService) ---

    public void attachChild(UUID parentId, UUID childId) {
        graph.attachChild(parentId, childId);
    }

    public void detachChild(UUID parentId, UUID childId) {
        graph.detachChild(parentId, childId);
    }

    // --- Members ---

    @Transactional
    public ResourceView attachMember(UUID id, MemberType memberType, String memberId) {
        Resource resource = resources.findByIdWithTypeKinds(id)
                .orElseThrow(() -> new NotFoundException("Resource not found."));
        requireMemberExists(memberType, memberId);
        resource.attachMember(new ResourceMember(memberType, memberId));
        return ResourceView.of(requireForView(id));
    }

    @Transactional
    public ResourceView detachMember(UUID id, MemberType memberType, String memberId) {
        Resource resource = require(id);
        resource.detachMember(member(memberType, memberId));
        return ResourceView.of(requireForView(id));
    }

    /** Builds a member value, turning a malformed GROUP/USER uuid into a 400 rather than a 500. */
    private ResourceMember member(MemberType memberType, String memberId) {
        if (memberType == MemberType.GROUP || memberType == MemberType.USER) {
            requireUuid(memberId);
        }
        return new ResourceMember(memberType, memberId);
    }

    // --- Delegation grants ---

    @Transactional
    public ResourceView assignAdmin(UUID id, UUID userId) {
        users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        Resource resource = require(id);
        resource.grant(ResourceGrant.admin(userId));
        return ResourceView.of(requireForView(id));
    }

    @Transactional
    public ResourceView revokeAdmin(UUID id, UUID userId) {
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
                groups.get(requireUuid(memberId)); // throws NotFoundException when absent
                yield true;
            }
            case USER -> users.findById(requireUuid(memberId)).isPresent();
            case APPLICATION -> applications.listApplications().stream()
                    .anyMatch(app -> app.id().equals(memberId));
            case RESOURCE -> throw new BadRequestException("Child resources are attached as edges, not members.");
        };
        if (!exists) {
            throw new NotFoundException("Member " + memberType + " not found.");
        }
    }

    /** A malformed uuid in the request body is the client's fault (400), not a server error (500). */
    private UUID requireUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Member id must be a UUID.");
        }
    }
}
