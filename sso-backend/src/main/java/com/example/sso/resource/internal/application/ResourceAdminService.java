package com.example.sso.resource.internal.application;

import com.example.sso.portal.ApplicationService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceEdge;
import com.example.sso.resource.internal.domain.ResourceEdgeRepository;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceGrantRow;
import com.example.sso.resource.internal.domain.ResourceGrantRowRepository;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceMemberRow;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceRoleTier;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserGroupService;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.UserService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management of the resource DAG: types, nodes, edges, members, and delegation grants. Every
 * referenced entity (member group/user/application, grantee) is validated to EXIST before it is
 * attached, so scope rows can never point at ids that were never real. Edges, members, grants and
 * type member-kinds are EXPLICIT rows this service inserts/deletes directly (no hidden JPA cascade),
 * so each write and each delete is visible here.
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
    private final ResourceTypeAllowedMemberRepository allowedMembers;
    private final ResourceEdgeRepository edges;
    private final ResourceMemberRowRepository memberRows;
    private final ResourceGrantRowRepository grantRows;
    private final ResourceGraphService graph;
    private final ResourceAccessPolicy access;
    private final UserService users;
    private final UserGroupService groups;
    private final ApplicationService applications;
    private final OrgTierGuard tierGuard;

    // --- Types ---

    @Transactional(readOnly = true)
    public List<ResourceTypeView> listTypes() {
        List<ResourceType> all = types.findAllByOrderByName();
        List<UUID> typeIds = all.stream().map(ResourceType::getId).toList();
        Map<UUID, List<MemberType>> allowedByType = allowedMembers.findByTypeIdIn(typeIds).stream()
                .collect(Collectors.groupingBy(ResourceTypeAllowedMember::getTypeId,
                        Collectors.mapping(ResourceTypeAllowedMember::getMemberType, Collectors.toList())));
        return all.stream()
                .map(type -> ResourceTypeView.of(type, allowedByType.getOrDefault(type.getId(), List.of())))
                .toList();
    }

    // create/createType are intentionally unscoped: they mint a DETACHED node/type with no grants or
    // edges, conferring no reach — a scoped admin can only wire it in later via the (both-endpoints) edge guard.
    @Transactional
    public ResourceTypeView createType(String name, Set<MemberType> allowedMemberTypes) {
        access.requireUnscoped(); // resource types are a GLOBAL vocabulary — platform super-admin only
        if (types.findByName(name).isPresent()) {
            throw new ConflictException("A resource type with this name already exists.");
        }
        ResourceType type = types.save(new ResourceType(name));
        allowedMemberTypes.forEach(memberType ->
                allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType)));
        return ResourceTypeView.of(type, allowedMemberTypes);
    }

    /** Deletes an unused resource type; rejects deletion while any resource still uses it (409). */
    @Transactional
    public void deleteType(UUID id) {
        access.requireUnscoped(); // global vocabulary — platform super-admin only
        ResourceType type = types.findById(id)
                .orElseThrow(() -> new NotFoundException("Resource type not found."));
        if (resources.existsByTypeId(id)) {
            throw new ConflictException("This type is still in use by one or more resources.");
        }
        allowedMembers.deleteByTypeId(id); // explicit: drop the member-kind rows before the type
        types.delete(type);
    }

    // --- Resources ---

    @Transactional(readOnly = true)
    public List<ResourceView> list() {
        boolean unscoped = access.isUnscoped();
        Set<UUID> managed = unscoped ? Set.of() : access.managedResourceIds();
        List<Resource> all = resources.findAllFetchingType();
        List<Resource> visible = all.stream()
                .filter(resource -> unscoped || managed.contains(resource.getId()))
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }

        // Child labels resolve against ALL resources (a child of a managed node is itself managed).
        Map<UUID, String> names = all.stream().collect(Collectors.toMap(Resource::getId, Resource::getName));
        List<UUID> ids = visible.stream().map(Resource::getId).toList();
        Map<UUID, List<ResourceEdge>> childEdges = edges.findByParentIdIn(ids).stream()
                .collect(Collectors.groupingBy(ResourceEdge::getParentId));
        Map<UUID, List<ResourceMemberRow>> membersByResource = memberRows.findByResourceIdIn(ids).stream()
                .collect(Collectors.groupingBy(ResourceMemberRow::getResourceId));
        Map<UUID, List<ResourceGrantRow>> grantsByResource = grantRows.findByResourceIdIn(ids).stream()
                .collect(Collectors.groupingBy(ResourceGrantRow::getResourceId));

        return visible.stream()
                .map(resource -> ResourceView.of(resource,
                        childEdges.getOrDefault(resource.getId(), List.of()), names,
                        membersByResource.getOrDefault(resource.getId(), List.of()),
                        grantsByResource.getOrDefault(resource.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ResourceView get(UUID id) {
        access.requireManage(id);
        requireInTier(id); // a tenant tier-admin's manage bypass is RLS-bounded; reject a global/foreign row
        return viewOf(id);
    }

    /**
     * Full detail for the scoped console: parents/children for DAG navigation plus members/grants with
     * their display labels resolved (group/app name, username). Scope-gated like {@link #get}.
     */
    @Transactional(readOnly = true)
    public ResourceDetailView detail(UUID id) {
        access.requireManage(id);
        Resource resource = requireInTierFetchingType(id);
        List<ResourceMemberRow> members = memberRows.findByResourceId(id);
        List<ResourceGrantRow> grants = grantRows.findByResourceId(id);

        // Parents are ANCESTORS — above the actor's grant. A scoped delegate must not learn about
        // ancestors outside their subtree, so filter to the ones they manage (a super admin sees all).
        boolean unscoped = access.isUnscoped();
        Set<UUID> managed = unscoped ? Set.of() : access.managedResourceIds();
        List<UUID> parentIds = edges.findByChildId(id).stream()
                .map(ResourceEdge::getParentId)
                .filter(parentId -> unscoped || managed.contains(parentId))
                .toList();
        List<ResourceNodeView> parents = nodes(parentIds);
        List<ResourceNodeView> children = nodes(edges.findByParentId(id).stream()
                .map(ResourceEdge::getChildId).toList());

        Map<String, String> groupNames = labels(groups.idNames(memberUuids(members, MemberType.GROUP)));
        Map<String, String> userNames = labels(users.idNames(userIdsToResolve(members, grants)));
        Map<String, String> appNames = appLabels(members);

        List<ResourceMemberDetailView> memberViews = members.stream()
                .map(member -> new ResourceMemberDetailView(member.getMemberType().name(), member.getMemberId(),
                        memberLabel(member.getMemberType(), member.getMemberId(), groupNames, userNames, appNames)))
                .sorted(Comparator.comparing(ResourceMemberDetailView::memberType)
                        .thenComparing(ResourceMemberDetailView::memberId))
                .toList();
        List<ResourceGrantDetailView> grantViews = grants.stream()
                .map(grant -> new ResourceGrantDetailView(grant.getUserId().toString(),
                        userNames.get(grant.getUserId().toString()), grant.getTier().name()))
                .sorted(Comparator.comparing(ResourceGrantDetailView::userId))
                .toList();

        return new ResourceDetailView(resource.getId().toString(), resource.getName(),
                resource.getType().getName(), parents, children, memberViews, grantViews);
    }

    /** Resolves the given resource ids to sorted (id, name) node views. */
    private List<ResourceNodeView> nodes(List<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return resources.findAllById(resourceIds).stream()
                .map(resource -> new ResourceNodeView(resource.getId().toString(), resource.getName()))
                .sorted(Comparator.comparing(ResourceNodeView::name))
                .toList();
    }

    private Map<String, String> labels(List<IdName> idNames) {
        return idNames.stream().collect(Collectors.toMap(idName -> idName.getId().toString(), IdName::getName));
    }

    private Set<UUID> memberUuids(List<ResourceMemberRow> members, MemberType type) {
        return members.stream()
                .filter(member -> member.getMemberType() == type)
                .map(member -> UUID.fromString(member.getMemberId()))
                .collect(Collectors.toSet());
    }

    private Set<UUID> userIdsToResolve(List<ResourceMemberRow> members, List<ResourceGrantRow> grants) {
        Set<UUID> ids = memberUuids(members, MemberType.USER);
        grants.forEach(grant -> ids.add(grant.getUserId()));
        return ids;
    }

    private String memberLabel(MemberType type, String memberId, Map<String, String> groupNames,
                               Map<String, String> userNames, Map<String, String> appNames) {
        return switch (type) {
            case GROUP -> groupNames.get(memberId);
            case USER -> userNames.get(memberId);
            case APPLICATION -> appNames.get(memberId);
            case RESOURCE -> null;
        };
    }

    /** App id→name labels, loaded only when the resource actually has APPLICATION members. */
    private Map<String, String> appLabels(List<ResourceMemberRow> members) {
        boolean hasApps = members.stream().anyMatch(member -> member.getMemberType() == MemberType.APPLICATION);
        if (!hasApps) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>(); // tolerate a null app name (HashMap allows null values)
        applications.listApplications().forEach(app -> names.put(app.id(), app.name()));
        return names;
    }

    @Transactional
    public ResourceView create(String name, String typeName) {
        ResourceType type = types.findByName(typeName)
                .orElseThrow(() -> new NotFoundException("Resource type not found."));
        // Stamp the acting admin's tier: a drilled-in super-admin creates the resource in that org, a platform
        // super-admin (no bound org) creates a GLOBAL one (org null).
        Resource saved = resources.save(new Resource(name, type, tierGuard.currentTier()));
        return viewOf(saved.getId());
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
        Resource parent = requireInTier(parentId);
        ResourceType type = types.findByName(typeName)
                .orElseThrow(() -> new NotFoundException("Resource type not found."));
        // A sub-resource inherits its parent's tenant (the tree stays within one org).
        Resource child = resources.save(new Resource(name, type, parent.getOrgId()));
        graph.attachChild(parentId, child.getId());
        return viewOf(child.getId());
    }

    @Transactional
    public ResourceView rename(UUID id, String name) {
        access.requireManage(id);
        Resource resource = requireInTierFetchingType(id);
        resource.rename(name);
        return viewOf(id);
    }

    @Transactional
    public void delete(UUID id) {
        access.requireManage(id);
        Resource resource = requireInTier(id);
        // Explicit teardown of everything the node owns before the node itself — no reliance on JPA
        // cascade (the DB FKs also cascade, but the deletes are spelled out here to be self-documenting).
        edges.deleteByParentIdOrChildId(id, id);
        memberRows.deleteByResourceId(id);
        grantRows.deleteByResourceId(id);
        resources.delete(resource);
    }

    // --- Edges (cycle-checked + serialized in ResourceGraphService) ---

    @Transactional
    public void attachChild(UUID parentId, UUID childId) {
        // Edge guard: must manage BOTH endpoints, or a scoped admin could graft an unmanaged subtree
        // under theirs (to absorb it) or attach their subtree under a parent to inherit upward. One tx
        // gives the scope check and the graph write a consistent snapshot (matches the other mutators, OSIV-off).
        access.requireManage(parentId);
        access.requireManage(childId);
        requireInTier(parentId); // both endpoints must be in the caller's tier, else a global/foreign 404
        requireInTier(childId);
        graph.attachChild(parentId, childId);
    }

    @Transactional
    public void detachChild(UUID parentId, UUID childId) {
        access.requireManage(parentId);
        requireInTier(parentId);
        graph.detachChild(parentId, childId);
    }

    // --- Members ---

    @Transactional
    public ResourceView attachMember(UUID id, MemberType memberType, String memberId) {
        access.requireManage(id);
        access.requireManagesMember(memberType, memberId); // pull-in guard
        Resource resource = requireInTierFetchingType(id);
        resource.requireCanAttachMember(memberType, allowedMemberTypes(resource.getType().getId()));
        requireMemberExists(memberType, memberId);
        memberRows.save(ResourceMemberRow.of(id, new ResourceMember(memberType, memberId), resource.getOrgId()));
        return viewOf(id);
    }

    @Transactional
    public ResourceView detachMember(UUID id, MemberType memberType, String memberId) {
        access.requireManage(id);
        requireInTier(id);
        if (memberType == MemberType.GROUP || memberType == MemberType.USER) {
            MemberIds.requireUuid(memberId); // malformed id → 400, not a 500 on the delete
        }
        memberRows.deleteMember(id, memberType, memberId); // no-op when absent
        return viewOf(id);
    }

    // --- Delegation grants ---

    @Transactional
    public ResourceView assignAdmin(UUID id, UUID userId) {
        // A tier-admin (or subtree admin, or super) may delegate — but only on a resource in their own tier
        // (requireInTier) and only to a user who belongs to that resource's org (requireGranteeInOrg), so a
        // tenant can never make a global outsider an admin over its subtree.
        access.requireManage(id);
        Resource resource = requireInTier(id);
        users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        access.requireGranteeInOrg(resource.getOrgId(), userId);
        grant(id, ResourceGrant.admin(userId), resource.getOrgId());
        return viewOf(id);
    }

    @Transactional
    public ResourceView revokeAdmin(UUID id, UUID userId) {
        // Symmetric reach with assignAdmin; no grantee-membership check — revoking a grant only ever removes
        // reach (safe even for a user who has since left the org).
        access.requireManage(id);
        requireInTier(id);
        grantRows.deleteByResourceIdAndUserIdAndTier(id, userId, ResourceRoleTier.ADMIN);
        return viewOf(id);
    }

    /**
     * Persists a grant, replacing any existing row of the same user+tier. Replacement is explicit —
     * delete the old row, then insert the new — because the DB primary key is {@code (resource, user,
     * tier)} while a grant also carries {@code roleId}: two rows with the same PK could not coexist.
     */
    private void grant(UUID resourceId, ResourceGrant grant, UUID orgId) {
        grantRows.deleteByResourceIdAndUserIdAndTier(resourceId, grant.userId(), grant.tier());
        grantRows.save(ResourceGrantRow.of(resourceId, grant, orgId));
    }

    private Resource requireFetchingType(UUID id) {
        return resources.findByIdFetchingType(id).orElseThrow(() -> new NotFoundException("Resource not found."));
    }

    // A tier-admin's manage bypass ({@link ResourceAccessPolicy#isTierAdmin}) is only RLS-bounded, and RLS lets
    // any context READ a GLOBAL (org null) row, so structure/read ops must additionally confirm the loaded
    // resource belongs to the caller's tier — a mismatch (a global row, or the platform's un-drilled context on
    // an org row) is a non-revealing 404, never a mutation on a resource the caller does not own.
    private Resource requireInTier(UUID id) {
        return tierGuard.requireInTier(resources.findById(id), () -> new NotFoundException("Resource not found."));
    }

    private Resource requireInTierFetchingType(UUID id) {
        return tierGuard.requireInTier(resources.findByIdFetchingType(id),
                () -> new NotFoundException("Resource not found."));
    }

    /** Projects the single-resource admin view, loading its explicit edge/member/grant rows. */
    private ResourceView viewOf(UUID id) {
        Resource resource = requireFetchingType(id);
        List<ResourceEdge> childEdges = edges.findByParentId(id);
        Map<UUID, String> childNames = resources.findAllById(childEdges.stream()
                        .map(ResourceEdge::getChildId).toList()).stream()
                .collect(Collectors.toMap(Resource::getId, Resource::getName));
        return ResourceView.of(resource, childEdges, childNames,
                memberRows.findByResourceId(id), grantRows.findByResourceId(id));
    }

    private Set<MemberType> allowedMemberTypes(UUID typeId) {
        return allowedMembers.findByTypeId(typeId).stream()
                .map(ResourceTypeAllowedMember::getMemberType)
                .collect(Collectors.toSet());
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
