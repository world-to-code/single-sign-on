package com.example.sso.resource.internal.application;

import com.example.sso.resource.GroupAuthorization;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-graph {@link GroupAuthorization}: a group is in scope when it is a GROUP member of one of
 * the actor's managed resources.
 */
@Service
@RequiredArgsConstructor
public class GroupAuthorizationImpl implements GroupAuthorization {

    private final ResourceScope scope;
    private final ResourceRepository resources;

    @Override
    @Transactional(readOnly = true)
    public boolean canView(UUID actorUserId, UUID groupId) {
        return canManage(actorUserId, groupId); // VIEWER-tier semantics arrive in Phase 2
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canManage(UUID actorUserId, UUID groupId) {
        return scope.isUnscoped(actorUserId) || scopedGroupIds(actorUserId).contains(groupId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> scopedGroupIds(UUID actorUserId) {
        Set<UUID> managed = scope.managedResourceIds(actorUserId);
        if (managed.isEmpty()) {
            return Set.of();
        }
        return resources.findMemberIds(managed, MemberType.GROUP.name()).stream()
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }
}
