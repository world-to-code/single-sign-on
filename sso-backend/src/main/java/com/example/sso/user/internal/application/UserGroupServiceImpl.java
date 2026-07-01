package com.example.sso.user.internal.application;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.GroupMembersPage;
import com.example.sso.user.GroupView;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserGroupRepository;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.internal.domain.UserGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link UserGroupService}: admin CRUD plus membership management. Membership is validated
 * against {@link AppUserRepository}; ids that do not resolve to a user are dropped. Returns the public
 * {@link GroupView} projection so the {@code UserGroup} entity never leaves the module.
 */
@Service
@RequiredArgsConstructor
public class UserGroupServiceImpl implements UserGroupService {

    private final UserGroupRepository repository;
    private final AppUserRepository users;

    @Override
    @Transactional(readOnly = true)
    public List<GroupView> listAll() {
        return repository.findAllByOrderByNameAsc().stream().map(UserGroupServiceImpl::toView).toList();
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
    @Transactional
    public GroupView create(String name, String description, String externalId, Set<UUID> memberIds) {
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("group name already exists");
        }
        UserGroup group = new UserGroup(name, description, externalId);
        group.setMembers(existingUserIds(memberIds));
        return toView(repository.save(group));
    }

    @Override
    @Transactional
    public GroupView update(UUID id, String name, String description, String externalId, Set<UUID> memberIds) {
        UserGroup group = require(id);
        if (group.isSystem()) {
            throw new ConflictException("the '" + group.getName() + "' system group cannot be edited");
        }
        repository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new ConflictException("group name already exists"); });
        group.rename(name);
        group.describe(description);
        group.assignExternalId(externalId);
        group.setMembers(existingUserIds(memberIds));
        return toView(repository.save(group));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        UserGroup group = require(id);
        if (group.isSystem()) {
            throw new ConflictException("the '" + group.getName() + "' system group cannot be deleted");
        }
        repository.delete(group);
    }

    @Override
    @Transactional
    public GroupView setMembers(UUID id, Set<UUID> memberIds) {
        UserGroup group = require(id);
        if (group.isSystem()) {
            throw new ConflictException("membership of the '" + group.getName() + "' system group is managed automatically");
        }
        group.setMembers(existingUserIds(memberIds));
        return toView(repository.save(group));
    }

    private UserGroup require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("group not found"));
    }

    /** Keeps only the ids that resolve to an existing user (unknown ids are dropped). */
    private Set<UUID> existingUserIds(Set<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Set.of();
        }
        return users.findAllById(memberIds).stream().map(AppUser::getId).collect(Collectors.toSet());
    }

    private static GroupView toView(UserGroup group) {
        List<String> memberIds = group.getMemberUserIds().stream().map(UUID::toString).toList();
        return new GroupView(group.getId().toString(), group.getName(), group.getDescription(),
                group.getExternalId(), memberIds, memberIds.size(), group.isSystem());
    }
}
