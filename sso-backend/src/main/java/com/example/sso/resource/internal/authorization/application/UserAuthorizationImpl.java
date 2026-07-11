package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.authorization.GroupAuthorization;
import com.example.sso.resource.authorization.UserAuthorization;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.user.group.UserGroupService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-graph {@link UserAuthorization}: a user is in scope when they are a direct USER member of
 * a managed resource, or a member of a group that is a GROUP member of one (per the plan:
 * {@code canManageUser} = target's groups ∩ managed groups ≠ ∅, extended with direct USER members).
 */
@Service
@RequiredArgsConstructor
public class UserAuthorizationImpl implements UserAuthorization {

    private final ResourceScope scope;
    private final GroupAuthorization groups;
    private final ResourceRepository resources;
    private final UserGroupService userGroups;

    @Override
    @Transactional(readOnly = true)
    public boolean canView(UUID actorUserId, UUID targetUserId) {
        return canManage(actorUserId, targetUserId); // VIEWER-tier semantics arrive in Phase 2
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canManage(UUID actorUserId, UUID targetUserId) {
        if (scope.isUnscoped(actorUserId)) {
            return true;
        }
        if (directUserMemberIds(actorUserId).contains(targetUserId)) {
            return true;
        }
        Set<UUID> scopedGroups = groups.scopedGroupIds(actorUserId);
        if (scopedGroups.isEmpty()) {
            return false;
        }
        return userGroups.groupIdsOf(targetUserId).stream().anyMatch(scopedGroups::contains);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> scopedUserIds(UUID actorUserId) {
        Set<UUID> scoped = new HashSet<>(directUserMemberIds(actorUserId));
        scoped.addAll(userGroups.memberIdsOf(groups.scopedGroupIds(actorUserId)));
        return scoped;
    }

    /** Users attached directly (member_type USER) to the actor's managed resources. */
    private Set<UUID> directUserMemberIds(UUID actorUserId) {
        Set<UUID> managed = scope.managedResourceIds(actorUserId);
        if (managed.isEmpty()) {
            return Set.of();
        }
        return resources.findMemberIds(managed, MemberType.USER.name()).stream()
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }
}
