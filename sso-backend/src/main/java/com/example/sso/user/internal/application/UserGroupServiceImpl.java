package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.shared.IdName;
import com.example.sso.shared.Page;
import com.example.sso.user.group.GroupDeletedEvent;
import com.example.sso.user.group.GroupMembersPage;
import com.example.sso.user.group.GroupMembership;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.account.Suggestion;
import com.example.sso.user.internal.group.domain.UserGroupMember;
import com.example.sso.user.internal.group.domain.UserGroupMemberRepository;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.group.domain.UserGroupRole;
import com.example.sso.user.internal.group.domain.UserGroupRoleRepository;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.user.internal.role.domain.RoleRepository;
import com.example.sso.user.internal.group.domain.UserGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link UserGroupService}: admin CRUD plus membership management. Membership is validated
 * against {@link AppUserRepository}; ids that do not resolve to a user are dropped. Membership rows
 * ({@code user_group_member}) and delegated-role rows ({@code group_role}) are written explicitly through
 * their repositories — no JPA-managed collection with cascade. Returns the public {@link GroupView}.
 */
@Service
@RequiredArgsConstructor
public class UserGroupServiceImpl implements UserGroupService {

    private final UserGroupRepository repository;
    private final UserGroupMemberRepository members;
    private final UserGroupRoleRepository groupRoles;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final ApplicationEventPublisher events;
    private final AccessChangePublisher accessChanges;
    private final OrgContext orgContext;
    private final RbacHydrator hydrator;

    /** The org a newly-created group belongs to: the active tenant context, or null (global/system group). */
    private UUID creationOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    /** A group with this name in the same tier (org, or global), or empty — for the uniqueness check. */
    private Optional<UserGroup> sameTierByName(String name, UUID org) {
        return org == null ? repository.findByNameAndOrgIdIsNull(name) : repository.findByNameAndOrgId(name, org);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupView> listAll() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GroupView> listAll(int page, int size) {
        return toPage(repository.findByOrderByNameAsc(PageRequest.of(Page.clampPage(page), Page.clampSize(size))));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GroupView> listByOrg(UUID orgId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Page.clampPage(page), Page.clampSize(size));
        // A null tier is the PLATFORM: the global/system groups only — an un-drilled super-admin never sees a
        // tenant's groups merged in; they drill into a tenant to get its (non-null) tier.
        return toPage(orgId == null
                ? repository.findByOrgIdIsNullOrderByNameAsc(pageRequest)
                : repository.findByOrgIdOrderByNameAsc(orgId, pageRequest));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GroupView> listByIds(Collection<UUID> ids, int page, int size) {
        int safePage = Page.clampPage(page);
        int safeSize = Page.clampSize(size);
        if (ids.isEmpty()) {
            return new Page<>(0, safePage, safeSize, List.of());
        }
        return toPage(repository.findByIdInOrderByNameAsc(ids, PageRequest.of(safePage, safeSize)));
    }

    private Page<GroupView> toPage(org.springframework.data.domain.Page<UserGroup> found) {
        return new Page<>(found.getTotalElements(), found.getNumber(), found.getSize(),
                found.getContent().stream().map(this::toView).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public GroupView get(UUID id) {
        return toView(require(id));
    }

    @Override
    @Transactional(readOnly = true)
    public GroupMembersPage members(UUID id, int page, int size) {
        int safeSize = size <= 0 ? 20 : Math.min(size, 100);
        int safePage = Math.max(page, 0);

        List<Suggestion> items = users.findGroupMembers(id, PageRequest.of(safePage, safeSize)).stream()
                .map(p -> new Suggestion(p.getId().toString(), p.getName())).toList();

        return new GroupMembersPage(repository.countMembers(id), safePage, safeSize, items);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Suggestion> search(String q, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 50);
        return repository.search(q == null ? "" : q, PageRequest.of(0, safeLimit)).stream()
                .map(p -> new Suggestion(p.getId().toString(), p.getName())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Suggestion> searchInOrg(String q, UUID orgId, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 50);
        return repository.searchInOrg(q == null ? "" : q, orgId, PageRequest.of(0, safeLimit)).stream()
                .map(p -> new Suggestion(p.getId().toString(), p.getName())).toList();
    }

    @Override
    @Transactional
    public GroupView create(GroupSpec spec) {
        UUID org = creationOrg();
        if (sameTierByName(spec.name(), org).isPresent()) { // unique within its own tier (global or this org)
            throw ConflictException.of("user.group.duplicate");
        }

        UserGroup group = repository.save(new UserGroup(spec.name(), spec.description(), spec.externalId(), org));
        insertMembers(group.getId(), existingUserIds(group, spec.memberIds()));

        return toView(group);
    }

    @Override
    @Transactional
    public GroupView update(UUID id, GroupSpec spec) {
        UserGroup group = require(id);
        if (group.isSystem()) {
            throw ConflictException.of("user.group.systemNoEdit", group.getName());
        }

        sameTierByName(spec.name(), group.getOrgId())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw ConflictException.of("user.group.duplicate"); });

        Set<UUID> affected = new HashSet<>(members.findUserIdsByGroupId(id)); // former members (roles may change too)
        group.rename(spec.name());
        group.describe(spec.description());
        group.assignExternalId(spec.externalId());
        replaceMembers(id, existingUserIds(group, spec.memberIds()));

        affected.addAll(members.findUserIdsByGroupId(id)); // and current members — both sets' delegated roles shift
        GroupView view = toView(repository.save(group));
        accessChanges.membershipChanged(affected);
        return view;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        UserGroup group = require(id);
        if (group.isSystem()) {
            throw ConflictException.of("user.group.systemNoDelete", group.getName());
        }

        Set<UUID> affected = new HashSet<>(members.findUserIdsByGroupId(id)); // members lose the group's delegated roles
        // Explicitly drop the group's membership and delegation rows before the group itself.
        members.deleteByGroupId(id);
        groupRoles.deleteByGroupId(id);
        repository.delete(group);
        events.publishEvent(new GroupDeletedEvent(id));
        accessChanges.membershipChanged(affected);
    }

    @Override
    @Transactional
    public GroupView setMembers(UUID id, Set<UUID> memberIds) {
        UserGroup group = require(id);
        if (group.isSystem()) {
            throw ConflictException.of("user.group.systemMembershipAuto", group.getName());
        }

        Set<UUID> affected = new HashSet<>(members.findUserIdsByGroupId(id));
        replaceMembers(id, existingUserIds(group, memberIds));

        affected.addAll(members.findUserIdsByGroupId(id)); // gained-or-lost members refresh their delegated roles
        GroupView view = toView(group);
        accessChanges.membershipChanged(affected);
        return view;
    }

    @Override
    @Transactional
    public void addMember(UUID groupId, UUID userId) {
        UserGroup group = require(groupId);
        if (group.isSystem()) {
            throw ConflictException.of("user.group.systemMembershipAuto", group.getName());
        }
        if (existingUserIds(group, Set.of(userId)).isEmpty()) {
            return; // unknown user (or dropped) — no-op; a cross-org user throws inside existingUserIds
        }
        members.save(new UserGroupMember(groupId, userId)); // idempotent via the composite PK
        accessChanges.membershipChanged(Set.of(userId));
    }

    @Override
    @Transactional
    public void addMembers(UUID groupId, Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        UserGroup group = require(groupId);
        if (group.isSystem()) {
            throw ConflictException.of("user.group.systemMembershipAuto", group.getName());
        }
        Set<UUID> valid = existingUserIds(group, userIds); // one same-org validation for the whole cohort
        insertMembers(groupId, valid);
        accessChanges.membershipChanged(valid); // single fan-out, not one event per user
    }

    @Override
    @Transactional
    public void removeMember(UUID groupId, UUID userId) {
        UserGroup group = require(groupId);
        if (group.isSystem()) {
            throw ConflictException.of("user.group.systemMembershipAuto", group.getName());
        }
        members.deleteByGroupIdAndUserId(groupId, userId);
        accessChanges.membershipChanged(Set.of(userId));
    }

    @Override
    @Transactional
    public GroupView setRoles(UUID id, Set<String> roleNames) {
        UserGroup group = require(id);
        if (group.isSystem()) {
            throw ConflictException.of("user.group.systemRolesNoEdit", group.getName());
        }

        replaceRoles(id, resolveRoleIds(roleNames));

        GroupView view = toView(group);
        // every member's delegated roles just changed
        accessChanges.forUserIds(new HashSet<>(members.findUserIdsByGroupId(id)));
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupMembership> membershipsForUser(UUID userId) {
        return repository.findByMember(userId).stream()
                .map(group -> new GroupMembership(group.getId(), group.getName(),
                        delegatedRoles(group.getId()).stream().map(RoleRef.class::cast).toList()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> memberIdsOf(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(repository.findMemberIdsByGroupIds(groupIds));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> groupIdsOf(UUID userId) {
        return Set.copyOf(repository.findGroupIdsByMember(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdName> idNames(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : repository.findIdNames(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> orgIdOf(UUID groupId) {
        // Map#map drops a null org, so a global group (org_id null) and an unknown id both yield empty: exactly
        // the "no org administers this group" answer the org-scope authorization check wants.
        return orgContext.callAsPlatform(() -> repository.findById(groupId).map(UserGroup::getOrgId));
    }

    private UserGroup require(UUID id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.of("user.group.notFound"));
    }

    /** The group's delegated roles, with permission names hydrated (consumed by the user-detail view). */
    private List<Role> delegatedRoles(UUID groupId) {
        List<UUID> roleIds = groupRoles.findRoleIdsByGroupId(groupId);
        return roleIds.isEmpty() ? List.of() : hydrator.hydrateRoles(roles.findAllById(new HashSet<>(roleIds)));
    }

    /** Adds an explicit membership row per user (idempotent via the composite PK). */
    private void insertMembers(UUID groupId, Set<UUID> userIds) {
        userIds.forEach(userId -> members.save(new UserGroupMember(groupId, userId)));
    }

    /** Replaces a group's membership wholesale: explicitly delete the removed rows, insert the added ones. */
    private void replaceMembers(UUID groupId, Set<UUID> desired) {
        Set<UUID> current = new HashSet<>(members.findUserIdsByGroupId(groupId));
        current.stream().filter(userId -> !desired.contains(userId))
                .forEach(userId -> members.deleteByGroupIdAndUserId(groupId, userId));
        desired.stream().filter(userId -> !current.contains(userId))
                .forEach(userId -> members.save(new UserGroupMember(groupId, userId)));
    }

    /** Replaces a group's delegated roles wholesale: explicitly delete the removed rows, insert the added ones. */
    private void replaceRoles(UUID groupId, Set<UUID> desired) {
        Set<UUID> current = new HashSet<>(groupRoles.findRoleIdsByGroupId(groupId));
        current.stream().filter(roleId -> !desired.contains(roleId))
                .forEach(roleId -> groupRoles.deleteByGroupIdAndRoleId(groupId, roleId));
        desired.stream().filter(roleId -> !current.contains(roleId))
                .forEach(roleId -> groupRoles.save(new UserGroupRole(groupId, roleId)));
    }

    /** Resolves role names to existing global {@link Role} ids; rejects an unknown name (400). */
    private Set<UUID> resolveRoleIds(Set<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return Set.of();
        }

        return roleNames.stream()
                // Groups delegate GLOBAL roles by name; tenant roles are managed per-org by id.
                .map(name -> roles.findByNameAndOrgIdIsNull(name)
                        .orElseThrow(() -> BadRequestException.of("user.role.unknown", name)).getId())
                .collect(Collectors.toSet());
    }

    /** Keeps only the ids that resolve to an existing user in the group's org (unknown ids are dropped). */
    private Set<UUID> existingUserIds(UserGroup group, Set<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Set.of();
        }

        // A group may contain only principals of its OWN org — never another tenant's user, nor (for a tenant
        // group) a global user. Ids that resolve to no user are dropped, as before; a resolved user in the
        // wrong org is a boundary violation (a clean 400, not a silent RLS reject on the member-row insert).
        List<AppUser> found = users.findAllById(memberIds);
        for (AppUser user : found) {
            if (!Objects.equals(user.getOrgId(), group.getOrgId())) {
                throw BadRequestException.of("user.group.crossOrgMember");
            }
        }
        return found.stream().map(AppUser::getId).collect(Collectors.toSet());
    }

    private GroupView toView(UserGroup group) {
        List<String> memberIds = members.findUserIdsByGroupId(group.getId()).stream().map(UUID::toString).toList();
        List<String> roleNames = roles.findAllById(groupRoles.findRoleIdsByGroupId(group.getId())).stream()
                .map(Role::getName).sorted().toList();

        return new GroupView(group.getId().toString(), group.getName(), group.getDescription(),
                group.getExternalId(), memberIds, memberIds.size(), group.isSystem(), roleNames);
    }
}
