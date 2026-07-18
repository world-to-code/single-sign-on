package com.example.sso.resource.internal.catalog.application;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.resource.internal.authorization.application.MemberIds;
import com.example.sso.resource.internal.authorization.application.ResourceAccessPolicy;
import com.example.sso.resource.internal.graph.application.ResourceGraphService;

import com.example.sso.portal.application.ApplicationService;
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
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.account.UserService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.example.sso.resource.catalog.ResourceDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final AttributeService attributes;
    private final UserService users;
    private final UserGroupService groups;
    private final ApplicationService applications;
    private final OrgTierGuard tierGuard;
    private final OrgContext orgContext;
    private final ApplicationEventPublisher events;

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

    // create/createType are intentionally unscoped w.r.t. the resource SUBTREE: they mint a DETACHED node/type
    // with no grants or edges, conferring no reach — a scoped admin can only wire it in later via the
    // (both-endpoints) edge guard. The type is org-stamped to the acting tier, so a tenant defines its OWN types.
    @Transactional
    public ResourceTypeView createType(String name, Set<MemberType> allowedMemberTypes) {
        UUID tier = tierGuard.currentTier(); // a drilled/bound tenant admin creates a tenant type; a platform
        // super-admin (no bound org) creates a GLOBAL/shared one (org null)
        boolean taken = tier == null
                ? types.findByNameAndOrgIdIsNull(name).isPresent()
                : types.findByNameAndOrgId(name, tier).isPresent();
        if (taken) {
            throw ConflictException.of("resource.type.duplicate");
        }
        ResourceType type = types.save(new ResourceType(name, tier));
        allowedMemberTypes.forEach(memberType ->
                allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType, tier)));
        return ResourceTypeView.of(type, allowedMemberTypes);
    }

    /** Deletes an unused resource type from the caller's own tier; rejects deletion while any resource uses it. */
    @Transactional
    public void deleteType(UUID id) {
        // Confined to the acting tier: a tenant deletes only its OWN types, the platform super only global ones.
        // A foreign/global type for a tenant (or a tenant type for the platform) is a non-revealing 404.
        ResourceType type = tierGuard.requireInTier(
                types.findById(id), () -> new NotFoundException("Resource type not found."));
        // Check for use RLS-BLIND: a global type may be referenced by another tenant's resources that the
        // acting (e.g. un-drilled super) context cannot see, so a plain check would report "unused" and let
        // the delete hit the FK → a 500. Platform scope sees every referencing resource → a clean 409.
        if (orgContext.callAsPlatform(() -> resources.existsByTypeId(id))) {
            throw ConflictException.of("resource.type.inUse");
        }
        allowedMembers.deleteByTypeId(id); // explicit: drop the member-kind rows before the type
        types.delete(type);
    }

    /** The type named {@code typeName} in the acting tier (a tenant's own first), else the GLOBAL one. */
    private ResourceType resolveType(String typeName) {
        UUID tier = tierGuard.currentTier();
        return (tier == null ? Optional.<ResourceType>empty() : types.findByNameAndOrgId(typeName, tier))
                .or(() -> types.findByNameAndOrgIdIsNull(typeName))
                .orElseThrow(() -> new NotFoundException("Resource type not found."));
    }

    // --- Resources ---

    @Transactional(readOnly = true)
    public List<ResourceView> list() {
        // Tier-scoped for a TIER admin (super or tenant): the resources of the ACTING tier — the bound org's
        // tree for a tenant admin or a drilled super-admin, and only the global (org-less) resources for an
        // un-drilled super-admin (never all tenants' trees merged). A mere resource delegate is narrowed to
        // their managed subtree (empty for a pure tenant admin, which would otherwise hide their org's tree).
        boolean tierAdmin = access.isTierAdmin();
        // A resource delegate sees every resource they can VIEW (ADMIN or VIEWER subtree) — a pure VIEWER would
        // otherwise get an empty list and never see the tree they were granted read access to.
        Set<UUID> viewable = tierAdmin ? Set.of() : access.viewableResourceIds();
        UUID tier = tierGuard.currentTier();
        List<Resource> all = resources.findAllFetchingType();
        List<Resource> visible = all.stream()
                .filter(resource -> tierAdmin
                        ? Objects.equals(resource.getOrgId(), tier)
                        : viewable.contains(resource.getId()))
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
        access.requireView(id); // read reach — a VIEWER-tier delegate may see, not mutate
        requireInTier(id); // a tenant tier-admin's manage bypass is RLS-bounded; reject a global/foreign row
        return viewOf(id);
    }

    /**
     * Full detail for the scoped console: parents/children for DAG navigation plus members/grants with
     * their display labels resolved (group/app name, username). Scope-gated like {@link #get}.
     */
    @Transactional(readOnly = true)
    public ResourceDetailView detail(UUID id) {
        access.requireView(id); // read reach — a VIEWER-tier delegate may see the detail, not mutate it
        Resource resource = requireInTierFetchingType(id);
        List<ResourceMemberRow> members = memberRows.findByResourceId(id);
        List<ResourceGrantRow> grants = grantRows.findByResourceId(id);

        // Parents are ANCESTORS — above a scoped delegate's grant, so they must not learn about ancestors outside
        // their subtree (filter to the ones they can VIEW). A TIER admin (platform super OR tenant ORG_ADMIN) sees
        // every ancestor in their own org tree — RLS already bounds the read to their org.
        boolean seesWholeTree = access.isTierAdmin();
        Set<UUID> viewable = seesWholeTree ? Set.of() : access.viewableResourceIds();
        List<UUID> parentIds = edges.findByChildId(id).stream()
                .map(ResourceEdge::getParentId)
                .filter(parentId -> seesWholeTree || viewable.contains(parentId))
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
        // The delegation roster (who else administers/views this subtree) is a MANAGEMENT concern — hide it from
        // a read-only VIEWER, who has no business learning the co-delegates' identities. Managers/tier-admins see it.
        List<ResourceGrantDetailView> grantViews = access.canManage(id)
                ? grants.stream()
                        .map(grant -> new ResourceGrantDetailView(grant.getUserId().toString(),
                                userNames.get(grant.getUserId().toString()), grant.getTier().name()))
                        .sorted(Comparator.comparing(ResourceGrantDetailView::userId))
                        .toList()
                : List.of();

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
        ResourceType type = resolveType(typeName);
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
        ResourceType type = resolveType(typeName);
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
        events.publishEvent(new ResourceDeletedEvent(id)); // let referrers (e.g. mapping rules) drop dangling refs
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
        attachMemberChecked(id, memberType, memberId);
        return viewOf(id);
    }

    /**
     * Attach a member enforcing only the INTEGRITY invariants (type allows the member kind, member exists, same
     * org) and the org-stamp — NO current-actor subtree authorization. The admin API calls this after its own
     * {@code requireManage}/{@code requireManagesMember} scope check; a programmatic caller
     * ({@link com.example.sso.resource.catalog.ResourceMembershipService}) calls it directly, having authorized
     * the operation its own way. Always runs inside the caller's transaction.
     */
    void attachMemberChecked(UUID id, MemberType memberType, String memberId) {
        Resource resource = requireInTierFetchingType(id);
        resource.requireCanAttachMember(memberType, allowedMemberTypes(resource.getType().getId()));
        requireMemberExists(memberType, memberId);
        requireMemberInResourceOrg(resource, memberType, memberId);
        memberRows.save(ResourceMemberRow.of(id, new ResourceMember(memberType, memberId), resource.getOrgId()));
    }

    @Transactional
    public ResourceView detachMember(UUID id, MemberType memberType, String memberId) {
        access.requireManage(id);
        detachMemberChecked(id, memberType, memberId);
        return viewOf(id);
    }

    /** Detach a member (idempotent) without the current-actor authorization — see {@link #attachMemberChecked}. */
    void detachMemberChecked(UUID id, MemberType memberType, String memberId) {
        requireInTier(id);
        if (memberType == MemberType.GROUP || memberType == MemberType.USER) {
            MemberIds.requireUuid(memberId); // malformed id → 400, not a 500 on the delete
        }
        memberRows.deleteMember(id, memberType, memberId); // no-op when absent
    }

    // --- Delegation grants ---

    /** Assigns an ADMIN (manage) grant — the default delegation tier. */
    @Transactional
    public ResourceView assignAdmin(UUID id, UUID userId) {
        return assignAdmin(id, userId, ResourceRoleTier.ADMIN);
    }

    @Transactional
    public ResourceView assignAdmin(UUID id, UUID userId, ResourceRoleTier tier) {
        // Delegating a grant (ADMIN = manage the subtree, VIEWER = read-only) requires MANAGE reach on the
        // resource — a VIEWER cannot re-delegate. Only on a resource in the caller's own tier (requireInTier)
        // and only to a user who belongs to that resource's org (requireGranteeInOrg), so a tenant can never
        // grant a global outsider reach over its subtree.
        access.requireManage(id);
        Resource resource = requireInTier(id);
        users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        access.requireGranteeInOrg(resource.getOrgId(), userId);
        grant(id, new ResourceGrant(userId, tier, null), resource.getOrgId());
        return viewOf(id);
    }

    @Transactional
    public ResourceView revokeAdmin(UUID id, UUID userId) {
        // Symmetric reach with assignAdmin; removes the user's grant of EITHER tier — no grantee-membership
        // check, since revoking only ever removes reach (safe even for a user who has since left the org).
        access.requireManage(id);
        requireInTier(id);
        grantRows.deleteByResourceIdAndUserId(id, userId);
        return viewOf(id);
    }

    // --- Metadata (key/value attributes) ---

    @Transactional(readOnly = true)
    public List<Attribute> attributesOf(UUID id) {
        access.requireManage(id);
        requireInTier(id); // reject a global/foreign resource — a tenant tags only its own tree
        return attributes.attributesOf(EntityKind.RESOURCE, id.toString());
    }

    @Transactional
    public List<Attribute> addAttribute(UUID id, String key, String value) {
        access.requireManage(id);
        requireInTier(id);
        attributes.add(EntityKind.RESOURCE, id.toString(), key, value); // accumulate — a key may hold several values
        return attributes.attributesOf(EntityKind.RESOURCE, id.toString());
    }

    @Transactional
    public List<Attribute> removeAttribute(UUID id, String key) {
        access.requireManage(id);
        requireInTier(id);
        attributes.remove(EntityKind.RESOURCE, id.toString(), key); // all of the key's values
        return attributes.attributesOf(EntityKind.RESOURCE, id.toString());
    }

    @Transactional
    public List<Attribute> removeAttributeValue(UUID id, String key, String value) {
        access.requireManage(id);
        requireInTier(id);
        attributes.removeValue(EntityKind.RESOURCE, id.toString(), key, value);
        return attributes.attributesOf(EntityKind.RESOURCE, id.toString());
    }

    /**
     * Persists a grant, replacing the user's existing grant on the resource REGARDLESS of tier — so assigning
     * VIEWER over an existing ADMIN (or vice versa) leaves exactly one grant, never both. Replacement is
     * explicit (delete then insert) rather than a merge, keeping one row per (resource, user).
     */
    private void grant(UUID resourceId, ResourceGrant grant, UUID orgId) {
        // One grant per (resource, user): drop any existing tier first, so assigning VIEWER replaces an ADMIN
        // grant (and vice versa) rather than leaving the user with both.
        grantRows.deleteByResourceIdAndUserId(resourceId, grant.userId());
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
            case RESOURCE -> throw BadRequestException.of("resource.member.childNotMember");
        };
        if (!exists) {
            throw new NotFoundException("Member " + memberType + " not found.");
        }
    }

    /**
     * A referenced directory principal must live in the SAME org as the resource — a tenant's resource may
     * reference only that tenant's users/groups, and a global resource only global ones; never bridge two
     * tenants nor a global resource to a tenant principal. Mirrors the resource-graph single-org invariant,
     * turning what RLS would otherwise reject as a WITH CHECK 500 into a clean 400. APPLICATIONs are a global
     * shared catalog (already org-scoped by the existence lookup's RLS), so they carry no org to match.
     */
    private void requireMemberInResourceOrg(Resource resource, MemberType memberType, String memberId) {
        if (memberType == MemberType.APPLICATION) {
            // A SYSTEM app (the first-party admin console / user portal) is a platform app, not a tenant one: it is
            // republished into every tenant's catalog but belongs to no org, and there is no legitimate reason to
            // make it a resource member. Refuse it on ANY resource — otherwise a delegated sub-admin under that
            // resource would gain management reach over a platform app. A peer tenant's org app is already absent
            // from the org-scoped catalog (existence 404s), so what remains is the caller's own org apps.
            boolean systemApp = applications.listApplications().stream()
                    .anyMatch(app -> app.id().equals(memberId) && app.system());
            if (systemApp) {
                throw BadRequestException.of("resource.member.systemApp");
            }
            return;
        }
        if (memberType != MemberType.USER && memberType != MemberType.GROUP) {
            return;
        }
        UUID member = MemberIds.requireUuid(memberId);
        UUID memberOrg = memberType == MemberType.USER
                ? users.findById(member).map(UserAccount::getOrgId).orElse(null)
                : groups.orgIdOf(member).orElse(null);
        if (!Objects.equals(resource.getOrgId(), memberOrg)) {
            throw BadRequestException.of("resource.member.crossOrg");
        }
    }
}
