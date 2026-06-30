package com.example.sso.user;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Write/read path for organizational groups: admin CRUD plus membership management. Groups are a
 * directory concern, SEPARATE from RBAC roles and the policy/app assignment subsystems. Membership
 * is validated against {@link AppUserRepository}; ids that do not resolve to a user are dropped.
 */
@Service
public class UserGroupService {

    private final UserGroupRepository repository;
    private final AppUserRepository users;

    public UserGroupService(UserGroupRepository repository, AppUserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<UserGroup> listAll() {
        return repository.findAllByOrderByNameAsc();
    }

    @Transactional
    public UserGroup create(String name, String description, String externalId, Set<UUID> memberIds) {
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("group name already exists");
        }
        UserGroup group = new UserGroup(name, description, externalId);
        group.setMembers(existingUserIds(memberIds));
        return repository.save(group);
    }

    @Transactional
    public UserGroup update(UUID id, String name, String description, String externalId, Set<UUID> memberIds) {
        UserGroup group = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("group not found"));
        repository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new ConflictException("group name already exists"); });
        group.rename(name);
        group.describe(description);
        group.assignExternalId(externalId);
        group.setMembers(existingUserIds(memberIds));
        return repository.save(group);
    }

    @Transactional
    public void delete(UUID id) {
        UserGroup group = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("group not found"));
        repository.delete(group);
    }

    @Transactional
    public UserGroup setMembers(UUID id, Set<UUID> memberIds) {
        UserGroup group = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("group not found"));
        group.setMembers(existingUserIds(memberIds));
        return repository.save(group);
    }

    /** Keeps only the ids that resolve to an existing user (unknown ids are dropped). */
    private Set<UUID> existingUserIds(Set<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Set.of();
        }
        return users.findAllById(memberIds).stream().map(AppUser::getId).collect(Collectors.toSet());
    }
}
